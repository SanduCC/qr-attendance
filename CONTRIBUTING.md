# Contributing

One change = one branch = one pull request. Nobody pushes to `master` directly: `master` is protected and a PR can
only be merged when every required check is green and the branch is up to date with `master`.

```
branch from master → code + tests → push → open PR → checks green → scripts/merge_pr.py (squash into master)
```

## Branches

Every branch starts with one of three prefixes, so the kind of change is visible at a glance:

| Prefix | Use it for | Example |
|---|---|---|
| `feature/` | anything new: app features, but also new CI jobs, docs, tooling | `feature/2-apple-login` |
| `bugfix/` | fixing a bug through the normal PR flow | `bugfix/27-csv-encoding` |
| `hotfix/` | an urgent fix for something already broken on `master` / in production | `hotfix/31-qr-token-expiry` |

After the prefix: the issue number (if there is one) and a short lowercase slug (`a-z 0-9 . _ -`). Link the issue in
the PR (`Closes #2`). Dependabot's own `dependabot/…` branches are the only exception. The `branch name` CI check
fails any PR whose branch doesn't follow this.

## Local checks before pushing

```bash
./mvnw verify      # unit + Testcontainers tests (the latter are skipped without Docker; CI always runs them)
```

## Checks that run on every PR

| Workflow | Job | What it enforces | Required |
|---|---|---|---|
| `ci.yml` | build and test | `./mvnw verify` on Corretto 25: compile, unit tests, Testcontainers integration tests (Postgres 16 + Redis 7) | yes |
| `ci.yml` | docker image build | the `Dockerfile` still builds | yes |
| `ci.yml` | branch name | PR branch is `feature/…`, `bugfix/…` or `hotfix/…` (or `dependabot/…`) | yes |
| `security.yml` | secret scan (gitleaks) | no secrets in any commit of the branch's history | yes |
| `security.yml` | SAST (semgrep) | Semgrep CE rules for Java, GitHub Actions and Dockerfile; on PRs only findings the PR adds fail | yes |
| `codeql.yml` | Analyze (java-kotlin / javascript-typescript / actions) | GitHub CodeQL `security-extended` queries; Java is built with Corretto 25. Alerts under *Security > Code scanning* | yes |

`security.yml` also runs a full scan every Monday, CodeQL every Sunday. Actions are pinned to commit SHAs with a
`# vX.Y.Z` comment.

## Merging

`master` is protected: changes only land through a pull request (admins included), **no approval is needed**, but
every required check must be green and the branch must be up to date with `master`. Only squash merges are allowed
(linear history) and merged branches are deleted automatically.

Either let GitHub merge it as soon as the checks pass:

```bash
gh pr merge <pr-number> --auto --squash
```

or use the script, which also refreshes a branch that has fallen behind and waits for the rerun checks:

```bash
python scripts/merge_pr.py <pr-number> --dry-run   # report only
python scripts/merge_pr.py <pr-number>             # refresh branch if behind, wait for checks, squash-merge, delete branch
```

The script refuses if a required check isn't green, the PR conflicts, is a draft, or targets anything but `master`.
When the branch is behind `master` it updates it, waits for the checks to rerun on the new commit and only then merges.
If you add or rename a CI job, update `REQUIRED_CHECKS` in the script **and** the required checks in the branch
protection rule (Settings > Branches > master), or PRs will wait forever for a check that no longer exists.

## Dependabot

Runs every Monday 05:00 (Europe/Bucharest) and opens PRs that go through the same checks:
- **maven**: minor + patch updates grouped in one PR, majors one per PR. Spring Boot majors are ignored (the 4.x
  upgrade is done by hand, issue #1).
- **github-actions**: all actions in one PR (updates the SHA and the version comment).
- **docker / docker-compose**: base images; Postgres/Redis majors are ignored.

Dependabot **alerts** and **security updates** are enabled on the repo, so vulnerable dependencies get fix PRs as
soon as an advisory is published, not just on Mondays.

## Rules that always apply

See [CLAUDE.md](CLAUDE.md): don't weaken an anti-cheating rule without agreement, every schema change is a new
Flyway migration, every anti-cheat rule has an integration test, test student pages at 375px.
