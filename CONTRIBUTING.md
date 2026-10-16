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
| `startup-benchmark.yml` | startup benchmark (informational) | only on PRs touching `Dockerfile`, `pom.xml` or `src/main`: the AOT cache is used, and cold-start times of the PR's image vs its base, in the job summary ([docs](docs/deployment.md#cold-start)) | no |

`security.yml` also runs a full scan every Monday, CodeQL every Sunday. Actions are pinned to commit SHAs with a
`# vX.Y.Z` comment.

## Checks on the deployed app (not on PRs)

| Workflow | Job | What it does | Required |
|---|---|---|---|
| `dast.yml` | ZAP baseline scan (informational) | OWASP ZAP **passive** baseline scan of production: spiders the public pages (≤ 1 min, 1 thread) and checks headers, CSP, cookies, caching and information leaks. Mondays 04:17 UTC, after every successful `deploy`, and by hand. Never fails on findings | no |

### DAST (ZAP baseline)

- Passive only, no login: ZAP never sends attack payloads to production, so it is safe during a class. Don't switch
  it to `zap-full-scan` / an active scan against production; that needs a separate, non-production target.
- Results: the job summary (counts per risk and one line per alert), the `zap-baseline-report` artifact (HTML, JSON,
  Markdown) and, for runs on `master`, one issue **"ZAP baseline report (production)"** that the action opens,
  comments on when the findings change and closes when none are left.
- Run it by hand: Actions > *DAST (ZAP baseline)* > Run workflow, or `gh workflow run dast.yml --ref <branch>`. A
  branch run writes no issue. The target is production unless the repo variable `DAST_TARGET_URL` (an https origin)
  says otherwise.
- Triage every finding: fix real ones in the app (headers live in `config/SecurityConfig.java`, with a test in
  `SecurityHeadersTest`). Accept a false positive or an intended behaviour only in `.zap/rules.tsv`, as
  `<rule id> TAB IGNORE TAB (<name>)` with a `#` line above it that says why.

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

A merge that touches the app deploys it to Cloud Run (`deploy.yml`, see
[docs/deployment.md](docs/deployment.md#continuous-deployment-github-actions--cloud-run)), so don't merge app changes
during a class, and keep Flyway migrations backward compatible with the previous release.

The script refuses if a required check isn't green, the PR conflicts, is a draft, or targets anything but `master`.
When the branch is behind `master` it updates it, waits for the checks to rerun on the new commit and only then merges.
If you add or rename a CI job, update `REQUIRED_CHECKS` in the script **and** the required checks in the branch
protection rule (Settings > Branches > master), or PRs will wait forever for a check that no longer exists.

## Dependabot

Runs every Monday 05:00 (Europe/Bucharest) and opens PRs that go through the same checks:
- **maven**: minor + patch updates grouped in one PR, majors one per PR. Spring Boot majors are ignored (the 4.x
  upgrade is done by hand, issue #1). The grouped minor/patch PR (`dependabot/maven/maven-minor-patch-*`) **merges
  itself**: `dependabot-auto-merge.yml` turns on auto-merge, and GitHub squash-merges once every required check is
  green. If it sits "behind master", comment `@dependabot rebase` on it.
- **github-actions**: all actions in one PR (updates the SHA and the version comment).
- **docker / docker-compose**: base images; Postgres/Redis majors are ignored.

Dependabot **alerts** and **security updates** are enabled on the repo, so vulnerable dependencies get fix PRs as
soon as an advisory is published, not just on Mondays.

## Rules that always apply

See [CLAUDE.md](CLAUDE.md): don't weaken an anti-cheating rule without agreement, every schema change is a new
Flyway migration, every anti-cheat rule has an integration test, test student pages at 375px.
