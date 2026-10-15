"""Merge a pull request into master only if it is safe: all checks green, no conflicts, branch up to date.

Adapted from OncoLens. Branch protection on master also requires the checks below; this script adds the convenience:
if the branch is behind master it is refreshed first (master merged into it via GitHub's update-branch) and the script
waits for the checks to rerun on the refreshed commit, so what lands on master was tested together with everything
already there. It then squash-merges and deletes the branch. GitHub's brief "mergeable: UNKNOWN" state after another
merge is waited out. Standard library only (Python 3.9+), needs the GitHub CLI (gh) logged in.

Usage:
    python scripts/merge_pr.py 12              # refresh if needed, check, merge PR #12
    python scripts/merge_pr.py 12 --dry-run    # only report (no refresh, no merge)
    python scripts/merge_pr.py 12 --no-update  # refuse instead of refreshing a branch that is behind
"""

import argparse
import json
import shutil
import subprocess
import sys
import time

REQUIRED_CHECKS = {
    "build and test", "docker image build",            # ci.yml
    "secret scan (gitleaks)", "SAST (semgrep)",        # security.yml
    "Analyze (java-kotlin)", "Analyze (javascript-typescript)", "Analyze (actions)",  # codeql.yml
}
FIELDS = "number,title,state,isDraft,baseRefName,headRefName,headRefOid,mergeable,statusCheckRollup"


def gh(*args):
    exe = shutil.which("gh") or r"C:\Program Files\GitHub CLI\gh.exe"
    result = subprocess.run([exe, *args], capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        sys.exit(f"gh {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def view(number):
    return json.loads(gh("pr", "view", str(number), "--json", FIELDS))


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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pr", type=int)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--no-update", action="store_true", help="don't refresh a branch that is behind master")
    parser.add_argument("--timeout", type=int, default=1800, help="seconds to wait for rerun checks")
    args = parser.parse_args()

    global REPO
    REPO = repo = json.loads(gh("repo", "view", "--json", "nameWithOwner"))["nameWithOwner"]
    pr = wait_until(args.pr, mergeability_known, 120, "GitHub to compute mergeability")
    behind = 0
    if pr["state"] == "OPEN":  # merged/closed PRs may have their branch deleted
        compare = f"repos/{repo}/compare/{pr['baseRefName']}...{pr['headRefName']}"
        behind = int(gh("api", compare, "--jq", ".behind_by"))

    if behind and not args.dry_run and not args.no_update and pr["mergeable"] == "MERGEABLE":
        print(f"PR #{args.pr} is {behind} commit(s) behind {pr['baseRefName']}: refreshing and rerunning checks...")
        old_head = pr["headRefOid"]
        gh("api", "-X", "PUT", f"repos/{repo}/pulls/{args.pr}/update-branch", "-f", f"expected_head_sha={old_head}")
        wait_until(args.pr, lambda p: p["headRefOid"] != old_head, 300, "the refreshed commit")
        time.sleep(20)  # let GitHub register the new check runs
        pr = wait_until(args.pr, checks_finished, args.timeout, "checks on the refreshed commit")
        pr = wait_until(args.pr, mergeability_known, 120, "GitHub to compute mergeability")
        behind = 0

    problems = []
    if pr["state"] != "OPEN":
        problems.append(f"PR is {pr['state']}")
    if pr["isDraft"]:
        problems.append("PR is a draft")
    if pr["baseRefName"] != "master":
        problems.append(f"base is {pr['baseRefName']}, not master")
    if pr["mergeable"] != "MERGEABLE":
        problems.append(f"not mergeable ({pr['mergeable']}): resolve conflicts")
    if behind:
        problems.append(f"branch is {behind} commit(s) behind master (run without --dry-run/--no-update to refresh)")
    checks = check_states(pr)
    for name in sorted(REQUIRED_CHECKS):
        if checks.get(name) != "SUCCESS":
            problems.append(f"check '{name}': {checks.get(name) or 'missing'}")

    print(f"PR #{pr['number']}: {pr['title']}")
    for name, state in sorted(checks.items()):
        print(f"  {state:>11}  {name}")
    if problems:
        print("NOT merging:")
        for problem in problems:
            print("  -", problem)
        sys.exit(1)
    if args.dry_run:
        print("All gates pass (dry run, not merged).")
        return
    gh("pr", "merge", str(args.pr), "--squash", "--delete-branch")
    print("Merged (squash) and deleted the branch.")


if __name__ == "__main__":
    main()
