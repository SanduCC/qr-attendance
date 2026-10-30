"""Merge a pull request into master only if it is safe: all checks green, no conflicts, branch up to date, and, for
a guarded PR, reviewed by the maintainer at its exact head commit.

Adapted from OncoLens. Branch protection on master also requires the checks below; this script adds the convenience:
if the branch is behind master it is refreshed first (master merged into it via GitHub's update-branch) and the script
waits for the checks to rerun on the refreshed commit, so what lands on master was tested together with everything
already there. It then squash-merges and deletes the branch. GitHub's brief "mergeable: UNKNOWN" state after another
merge is waited out. Standard library only (Python 3.9+), needs the GitHub CLI (gh) logged in.

Green checks prove nothing about a PR that can rewrite them: the job names stay the same if a PR edits
.github/workflows/ (or mvnw, pom.xml, the tests...), so branch protection is satisfied. The script is therefore the
human-review gate. A PR is GUARDED when it comes from a fork, when its author is not the maintainer or Dependabot, or
when it touches a sensitive path (SENSITIVE_DIRS / SENSITIVE_FILES below; also when its file list can't be read in
full). A guarded PR is merged only with --reviewed-sha set to its current head commit, i.e. the commit you read the
full diff of. It is never refreshed automatically (that would change the head after your review): if it is behind
master, update it, wait for the checks and review the new head. Every merge passes --match-head-commit, so a push
between the checks and the merge makes it fail instead of landing an unchecked commit.

Usage:
    python scripts/merge_pr.py 12              # refresh if needed, check, merge PR #12
    python scripts/merge_pr.py 12 --dry-run    # only report (no refresh, no merge), including the guard result
    python scripts/merge_pr.py 12 --no-update  # refuse instead of refreshing a branch that is behind
    python scripts/merge_pr.py 12 --reviewed-sha <40-char head sha>   # merge a guarded PR you reviewed at that commit
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
import time

REQUIRED_CHECKS = {
    "branch name", "build and test", "docker image build",  # ci.yml
    "secret scan (gitleaks)", "SAST (semgrep)",        # security.yml
    "Analyze (java-kotlin)", "Analyze (javascript-typescript)", "Analyze (actions)",  # codeql.yml
}
FIELDS = ("number,title,state,isDraft,baseRefName,headRefName,headRefOid,mergeable,statusCheckRollup,"
          "author,isCrossRepository,changedFiles")

# Who may open an unguarded PR. gh reports Dependabot as "app/dependabot", the REST API as "dependabot[bot]".
TRUSTED_AUTHORS = {"sanducc", "app/dependabot", "dependabot[bot]"}
# Paths that can change what the checks run or how the build/image is made: a PR touching them is reviewed by hand.
SENSITIVE_DIRS = (".github/", ".mvn/", "scripts/", "src/test/", ".zap/")
SENSITIVE_FILES = {"mvnw", "mvnw.cmd", "pom.xml", "dockerfile", ".dockerignore", ".gitattributes"}
FILES_API_CAP = 3000  # GitHub lists at most this many files of a PR
SHA = re.compile(r"[0-9a-f]{40}")


def gh(*args):
    exe = shutil.which("gh") or r"C:\Program Files\GitHub CLI\gh.exe"
    result = subprocess.run([exe, *args], capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        sys.exit(f"gh {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def view(number):
    return json.loads(gh("pr", "view", str(number), "--json", FIELDS))


def changed_files(number):
    """Every path the PR touches, including the old name of a renamed file. Read through the REST API with
    pagination, because `gh pr view --json files` is capped. One JSON object per line keeps odd file names intact."""
    out = gh("api", "--paginate", f"repos/{REPO}/pulls/{number}/files?per_page=100",
             "--jq", ".[] | {filename, previous_filename} | tojson")
    entries = [json.loads(line) for line in out.splitlines() if line.strip()]
    paths = []
    for entry in entries:
        paths.append(entry["filename"])
        if entry.get("previous_filename"):
            paths.append(entry["previous_filename"])
    return paths, len(entries)


def is_sensitive(path):
    """True for a path that can change the checks, the build or the image (compared case-insensitively)."""
    p = path.replace("\\", "/").lstrip("/").lower()
    return p in SENSITIVE_FILES or p.startswith(SENSITIVE_DIRS)


def author_login(pr):
    return ((pr.get("author") or {}).get("login") or "").strip()


def guard_reasons(author, cross_repository, paths, listed, total):
    """Why a PR needs a human review before it is merged (empty list: it doesn't), plus the sensitive paths.
    listed = files the API returned, total = the PR's changed-file count."""
    reasons = []
    if cross_repository:
        reasons.append("PR comes from a fork")
    if author.lower() not in TRUSTED_AUTHORS:
        reasons.append(f"author '{author or 'unknown'}' is not the maintainer or Dependabot")
    sensitive = sorted({p for p in paths if is_sensitive(p)})
    if sensitive:
        reasons.append(f"touches {len(sensitive)} sensitive path(s)")
    if listed < total or total >= FILES_API_CAP:
        reasons.append(f"file list incomplete ({listed} of {total} files readable): treated as sensitive")
    return reasons, sensitive


def review_problem(reasons, reviewed_sha, head_sha):
    """None if the PR may be merged as far as the review gate goes, otherwise what is missing."""
    if not reasons:
        return None
    if not reviewed_sha:
        return "guarded PR: no --reviewed-sha given"
    if reviewed_sha.lower() != (head_sha or "").lower():
        return f"guarded PR: --reviewed-sha {reviewed_sha} is not the current head {head_sha} (new commits since review?)"
    return None


def guard(pr):
    paths, listed = changed_files(pr["number"])
    return guard_reasons(author_login(pr), pr.get("isCrossRepository"), paths, listed, pr.get("changedFiles") or 0)


def print_guard(pr, reasons, sensitive, reviewed_sha):
    if not reasons:
        print(f"Guard: not guarded (author {author_login(pr)}, same repo, no sensitive paths).")
        return
    print("Guard: GUARDED, merge only after reading the full diff of the head commit:")
    for reason in reasons:
        print("  -", reason)
    for path in sensitive:
        print("      ", path)
    head = pr["headRefOid"]
    if review_problem(reasons, reviewed_sha, head) is None:
        print(f"  reviewed at {head}: OK")
    else:
        print(f"  Review: gh pr diff {pr['number']}   (head {head})")
        print(f"  Then:   python scripts/merge_pr.py {pr['number']} --reviewed-sha {head}")


def check_states(pr):
    """Check runs for the PR's current head commit, read by SHA. statusCheckRollup can still show the previous
    commit's results for a short while after a push, which would let an untested commit through."""
    runs = json.loads(gh("api", f"repos/{REPO}/commits/{pr['headRefOid']}/check-runs?per_page=100"))["check_runs"]
    states = {}
    for run in sorted(runs, key=lambda r: r.get("started_at") or ""):  # latest attempt of each check wins
        states[run["name"]] = (run.get("conclusion") or run.get("status") or "").upper()
    return states


def wait_until(number, done, timeout, what):
    """Poll the PR until done(pr) is true; exit with a message after timeout seconds."""
    deadline = time.time() + timeout
    while True:
        pr = view(number)
        if done(pr):
            return pr
        if time.time() > deadline:
            sys.exit(f"Timed out after {timeout // 60} min waiting for {what}.")
        time.sleep(15)


PENDING = (None, "QUEUED", "IN_PROGRESS", "PENDING", "WAITING", "REQUESTED")
REPO = None


def mergeability_known(pr):
    """GitHub reports mergeable=UNKNOWN briefly after other merges, and forever for closed PRs."""
    return pr["mergeable"] != "UNKNOWN" or pr["state"] != "OPEN"


def checks_finished(pr):
    states = check_states(pr)
    return all(states.get(name) not in PENDING for name in REQUIRED_CHECKS)


def full_sha(value):
    if not SHA.fullmatch(value.lower()):
        raise argparse.ArgumentTypeError("must be the full 40-character commit SHA")
    return value.lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pr", type=int)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--no-update", action="store_true", help="don't refresh a branch that is behind master")
    parser.add_argument("--reviewed-sha", type=full_sha, metavar="SHA",
                        help="head commit of a guarded PR whose full diff you reviewed")
    parser.add_argument("--timeout", type=int, default=1800, help="seconds to wait for rerun checks")
    args = parser.parse_args()

    global REPO
    REPO = repo = json.loads(gh("repo", "view", "--json", "nameWithOwner"))["nameWithOwner"]
    pr = wait_until(args.pr, mergeability_known, 120, "GitHub to compute mergeability")
    reasons, sensitive = guard(pr) if pr["state"] == "OPEN" else ([], [])
    unreviewed = review_problem(reasons, args.reviewed_sha, pr["headRefOid"])
    if unreviewed and not args.dry_run:  # refuse before any refresh or waiting
        print(f"PR #{pr['number']}: {pr['title']}")
        print_guard(pr, reasons, sensitive, args.reviewed_sha)
        sys.exit(f"NOT merging: {unreviewed}.")
    behind = 0
    if pr["state"] == "OPEN":  # merged/closed PRs may have their branch deleted
        compare = f"repos/{repo}/compare/{pr['baseRefName']}...{pr['headRefName']}"
        behind = int(gh("api", compare, "--jq", ".behind_by"))

    # A refresh would create a head commit nobody reviewed, so guarded PRs are never refreshed.
    if behind and not args.dry_run and not args.no_update and not reasons and pr["mergeable"] == "MERGEABLE":
        print(f"PR #{args.pr} is {behind} commit(s) behind {pr['baseRefName']}: refreshing and rerunning checks...")
        old_head = pr["headRefOid"]
        gh("api", "-X", "PUT", f"repos/{repo}/pulls/{args.pr}/update-branch", "-f", f"expected_head_sha={old_head}")
        wait_until(args.pr, lambda p: p["headRefOid"] != old_head, 300, "the refreshed commit")
        time.sleep(20)  # let GitHub register the new check runs
        pr = wait_until(args.pr, checks_finished, args.timeout, "checks on the refreshed commit")
        pr = wait_until(args.pr, mergeability_known, 120, "GitHub to compute mergeability")
        behind = 0
    elif pr["state"] == "OPEN" and not args.dry_run and not behind:  # still behind: refused below, don't wait
        # Checks may still be running on a fresh push: wait for them instead of refusing.
        pr = wait_until(args.pr, checks_finished, args.timeout, "checks to finish")
        pr = wait_until(args.pr, mergeability_known, 120, "GitHub to compute mergeability")

    if pr["state"] == "MERGED":  # GitHub auto-merge (gh pr merge --auto) can land it while we wait
        print(f"PR #{pr['number']}: {pr['title']} is already merged.")
        return

    if pr["state"] == "OPEN" and not args.dry_run:
        # Decide again on the commit that will be merged: the head may have moved while we waited.
        reasons, sensitive = guard(pr)
        unreviewed = review_problem(reasons, args.reviewed_sha, pr["headRefOid"])

    problems = []
    if pr["state"] != "OPEN":
        problems.append(f"PR is {pr['state']}")
    if pr["isDraft"]:
        problems.append("PR is a draft")
    if pr["baseRefName"] != "master":
        problems.append(f"base is {pr['baseRefName']}, not master")
    if pr["mergeable"] != "MERGEABLE":
        problems.append(f"not mergeable ({pr['mergeable']}): resolve conflicts")
    if behind and reasons:
        problems.append(f"branch is {behind} commit(s) behind master and guarded PRs are not refreshed automatically: "
                        "update it (or comment '@dependabot rebase'), wait for the checks, review the new head")
    elif behind:
        problems.append(f"branch is {behind} commit(s) behind master (run without --dry-run/--no-update to refresh)")
    checks = check_states(pr)
    for name in sorted(REQUIRED_CHECKS):
        if checks.get(name) != "SUCCESS":
            problems.append(f"check '{name}': {checks.get(name) or 'missing'}")
    if unreviewed and not args.dry_run:
        problems.append(unreviewed)

    print(f"PR #{pr['number']}: {pr['title']}")
    for name, state in sorted(checks.items()):
        print(f"  {state:>11}  {name}")
    if pr["state"] == "OPEN":
        print_guard(pr, reasons, sensitive, args.reviewed_sha)
    if problems:
        print("NOT merging:")
        for problem in problems:
            print("  -", problem)
        sys.exit(1)
    if args.dry_run:
        if unreviewed:  # reported, not fatal: a dry run is how you get the SHA to review
            print(f"Other gates pass (dry run, not merged). A real merge refuses until reviewed: {unreviewed}.")
        else:
            print("All gates pass (dry run, not merged).")
        return
    # The head must still be the commit checked (and, if guarded, reviewed) above, or GitHub refuses the merge.
    gh("pr", "merge", str(args.pr), "--squash", "--delete-branch", "--match-head-commit", pr["headRefOid"])
    print(f"Merged (squash) {pr['headRefOid'][:12]} and deleted the branch.")


if __name__ == "__main__":
    main()
