# QR Attendance

[![CI](https://github.com/SanduCC/qr-attendance/actions/workflows/ci.yml/badge.svg)](https://github.com/SanduCC/qr-attendance/actions/workflows/ci.yml)
[![security](https://github.com/SanduCC/qr-attendance/actions/workflows/security.yml/badge.svg)](https://github.com/SanduCC/qr-attendance/actions/workflows/security.yml)

Students scan a rotating QR code shown in class, sign in with Google (Apple later), and get recorded in the day's attendance table. Each student can check in once per session, and each phone only once per session.

It runs as a mobile web app / PWA, so it works on Android and iOS with no app store.

**Live app:** <https://qr-attendance-1057355191791.europe-west1.run.app>. It runs on Google Cloud Run (europe-west1, Belgium), and the data stays in the EU: Neon Postgres in Frankfurt and Upstash Redis in Belgium.

See [CLAUDE.md](CLAUDE.md) for the full spec, anti-cheating rules and conventions, and [docs/deployment.md](docs/deployment.md) for hosting options.

## Who does what

| Role | Who | Can |
|---|---|---|
| **Admin** | only the Google accounts listed in `ADMIN_EMAILS` (server config, not editable in the app) | everything a teacher can, plus manage teachers at `/admin/teachers`. Only admins see the **Teachers** tab |
| **Teacher** | a Google account an admin added at `/admin/teachers` | create courses, run attendance sessions, export attendance. Teachers see only their own courses |
| **Student** | anyone with a Google account who scans a live QR code | check in to that session |

- Nobody becomes a teacher just by signing in. An admin adds the teacher's Google email first.
- Admins can **deactivate** a teacher, and access stops immediately: every teacher request checks the database, not only the role given at sign-in. The teacher's courses and attendance are kept. **Reactivating** them, or adding the same email again, restores the same account. Admins can't be deactivated from the UI.

## Teacher flow

1. **My courses** (`/teacher/courses`) is the home page and shows one card per course. Each card shows the course code (if any) and the last session's date and count, e.g. "23 of 25 checked in".
2. **Start attendance** is one click. It opens a 15-minute session (configurable) and reuses the expected headcount from the course's last session. The **Options** panel lets you set a different duration or expected headcount. The projector page opens straight away.
3. **New course** only needs a name. The course code is optional.
4. **History & exports** (`/teacher/courses/{id}`) lists every session of the course with its time window, Open/Closed status, count, and **Display / Excel / CSV** links.
5. The **projector page** (`/sessions/{id}/display`) shows:
   - a big QR code that rotates every 15 s
   - a live "23 checked in / 25 expected" counter, with a warning when check-ins exceed the expected headcount
   - a **Closes in mm:ss** countdown and a **Fullscreen** button (where the browser supports it)
   - a live attendance table, refreshed every 3 s, where you can remove a suspicious entry, **Edit** a student's name, or **Add entry** by hand for someone who couldn't scan (see below)
   - **Close session**. When the session is closed, the page shows the final count and the download buttons.

### Fixing the list: names and manual entries

The attendance table on the projector page (also for a closed session, opened from the course history) lets the teacher fix the list:

- **Edit** corrects a student's first and last name, with the same rules as the student's own name form (letters, spaces, hyphens, apostrophes, dots; trimmed). The name belongs to the student, so the correction shows in every session and every export. Students still can't change their own name after the first check-in.
- **Add entry** adds someone who is in the room but couldn't scan (dead phone, no account). Pick one of the course's students (anyone who has checked in to it before), or type a name for someone without an account. The entry:
  - is marked **manual** in the table and in the exports, and records which teacher added it;
  - has no device or IP hash, so it never blocks a real check-in from a phone, and real check-ins keep all their anti-cheat checks;
  - counts in the counter and the headcount warning;
  - can't be added twice: a student who is already in the session (scanned or added) gets "Already checked in at 09:02", and a student added by hand who scans later sees the same;
  - can be removed like any other entry, and is purged with its session after the academic year.
- Both work while the session is open and after it has closed. Every change is logged with ids only (teacher, record, student, session), never names.

### Exports

Each session can be downloaded in two formats:

- **Excel (.xlsx)** (`/sessions/{id}/export.xlsx`)
  - a title block: course name and code, then date, time window and "checked in / expected"
  - columns **Nr. · Last name · First name · Email · Check-in time · Source**, sorted in Romanian alphabetical order (Ă, Â, Î, Ș, Ț in their proper places). Source is `scan` or `manual` (added by the teacher; bold in Excel), and the title block says how many were manual
  - a frozen, filterable header row, set up to print on A4
- **CSV** (`/sessions/{id}/export.csv`): same columns and order, with a `;` separator and a UTF-8 BOM, so Excel with a Romanian/EU locale opens it in columns with the diacritics intact.

Files are named `<course code>_<date>`, or after the course name when there is no code.

## Student flow

1. Scan the QR code with the phone camera. This opens `/a/{sessionId}?t=…`, and the server checks the token **before** sign-in.
2. A short privacy notice is shown, then **Continue with Google**. In-app browsers (WhatsApp, Instagram, …) can't do Google sign-in, so the page detects them and asks the student to open the link in Chrome or Safari instead.
3. The first time only, the student confirms their first and last name, which are pre-filled from Google. After that the names are locked; only a teacher can correct them.
4. A success screen shows the student's name and check-in time. Scanning again shows "Already checked in at 09:02".

### Anti-cheat (summary)

| Rule | How |
|---|---|
| Rotating QR | HMAC-SHA256 token per 15 s window; current + previous window accepted (~30 s) |
| Scan-time validation | Token checked before login and bound to the browser's HTTP session (and device cookie); 3 min grace period to finish sign-in |
| One check-in per account | DB unique constraint `(session_id, student_id)`; students are keyed on the Google `sub`, never email or name |
| One check-in per device | Signed httpOnly `device_id` cookie, only its hash stored; DB unique `(session_id, device_id_hash)` |
| Headcount check | Display warns when check-ins exceed the expected headcount (manual entries count too) |
| Manual entries | Teacher-only, marked `manual` with the teacher's id; no device/IP hash, so they never block or weaken a real scan. A DB check keeps every real scan's device and IP hash mandatory |
| Rate limiting | Per IP (120/min, since a whole class can share one Wi-Fi IP) and per device (10/min), in Redis |
| Later | Geofence (#3), Google Workspace domain lock (#4) |

IP addresses and device IDs are stored only as peppered hashes. Attendance data is purged after the academic year ends (default 30 Sep plus 30 days' grace).

## Stack

Amazon Corretto 25 (Java 25) · Spring Boot 3.5 · PostgreSQL 16 + Flyway · Redis 7 (HTTP sessions via Spring Session, rate limiting) · Thymeleaf + vanilla JS · Docker / docker compose · JUnit 5 + Testcontainers

## Quick start (local)

**Prerequisites:** JDK 25 (e.g. [Amazon Corretto 25](https://docs.aws.amazon.com/corretto/)), Docker (for Postgres/Redis and for integration tests), a Google OAuth client (see below).

1. Start the dependencies:

   ```sh
   docker compose up -d postgres redis
   ```

2. Configure the environment. Copy `.env.example` to `.env` and fill in the values (`.env` is gitignored), then load it into your shell. Spring Boot does not read `.env` by itself.

   Bash / Git Bash:

   ```bash
   cp .env.example .env        # then edit .env
   set -a; source .env; set +a
   ```

   PowerShell (current session only):

   ```powershell
   Copy-Item .env.example .env  # then edit .env
   Get-Content .env | Where-Object { $_ -match '^\s*[^#\s][^=]*=' } | ForEach-Object {
     $k, $v = $_ -split '=', 2
     Set-Item -Path "env:$($k.Trim())" -Value $v.Trim()
   }
   ```

   Or set just the required ones by hand:

   ```bash
   export GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... ADMIN_EMAILS=you@gmail.com
   export QR_HMAC_SECRET="$(openssl rand -base64 48)" HASH_PEPPER="$(openssl rand -base64 48)"
   ```

   ```powershell
   $env:GOOGLE_CLIENT_ID = "..."; $env:GOOGLE_CLIENT_SECRET = "..."; $env:ADMIN_EMAILS = "you@gmail.com"
   $env:QR_HMAC_SECRET = "<random, >= 32 chars>"; $env:HASH_PEPPER = "<random, >= 32 chars>"
   ```

3. Run the app (uses the `local` profile):

   ```sh
   ./mvnw spring-boot:run          # Windows cmd/PowerShell: .\mvnw.cmd spring-boot:run
   ```

4. Open <http://localhost:8080> and sign in with an email listed in `ADMIN_EMAILS`. Admins are teachers too;
   add other teachers at <http://localhost:8080/admin/teachers>.

To run the whole stack in containers instead, use `docker compose up -d --build` (secrets come from `.env`).

## Google OAuth client setup

1. [Google Cloud Console](https://console.cloud.google.com/) → create or select a project.
2. **APIs & Services → OAuth consent screen** (Google Auth Platform → Branding / Audience): app name, support email, scopes `openid`, `email`, `profile`. While in *Testing*, add your test users; publish to *In production* for a real class (basic scopes don't need Google verification).
3. **APIs & Services → Credentials → Create credentials → OAuth client ID**, type **Web application**.
4. **Authorized redirect URIs:**
   - `http://localhost:8080/login/oauth2/code/google` (local)
   - `https://<your-production-host>/login/oauth2/code/google` (production)
   - `https://<your-tunnel-host>/login/oauth2/code/google` (phone testing, see below)
5. Copy the client ID and secret into `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`.

The redirect URI is always `{PUBLIC_BASE_URL}/login/oauth2/code/google`.

## Testing on a real phone

Google OAuth only allows plain `http` for `localhost`, and the phone can't reach your `localhost` anyway, so expose the app over HTTPS with a tunnel:

```sh
cloudflared tunnel --url http://localhost:8080
```

Then:

1. Set `PUBLIC_BASE_URL` to the printed `https://<random>.trycloudflare.com` URL (it is encoded in the QR code).
2. Set `FORWARD_HEADERS_STRATEGY=framework` so the app trusts the tunnel's `X-Forwarded-*` headers and builds `https` redirect URLs.
3. Add `https://<random>.trycloudflare.com/login/oauth2/code/google` to the OAuth client's redirect URIs.
4. Restart the app, open the display page on your laptop and scan it with the phone camera.

Quick-tunnel URLs change on every start; a named Cloudflare tunnel (or any tunnel with a fixed hostname) saves re-registering the redirect URI. Test in Chrome Android, Samsung Internet and Safari iOS. Also open the link from an in-app browser (WhatsApp/Instagram): the app should tell the user to open it in Chrome/Safari.

## Running tests

```sh
./mvnw test       # unit + integration tests
./mvnw verify     # full build
```

Integration tests use Testcontainers (PostgreSQL 16 + Redis 7). They are **skipped automatically when Docker isn't running**. CI (GitHub Actions, see [`.github/workflows/ci.yml`](.github/workflows/ci.yml)) always runs them and also checks that the Docker image builds.

## Configuration

All configuration comes from environment variables. Secrets are never committed; generate them with e.g. `openssl rand -base64 48`.

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `8080` | HTTP port (Cloud Run sets it) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/attendance` | JDBC URL |
| `DATABASE_USERNAME` | `attendance` | |
| `DATABASE_PASSWORD` | `attendance` | |
| `REDIS_URL` | `redis://localhost:6379` | use `rediss://` for TLS (e.g. Upstash). **Required**: HTTP sessions live here (see below) |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | base URL encoded in the QR code |
| `ADMIN_EMAILS` | *(empty)* | comma-separated admin Google emails (replaces the old `TEACHER_EMAILS`). Admins are teachers and the only ones who can add/deactivate teachers (`/admin/teachers`); nobody becomes a teacher by signing in |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | **required** | Google OAuth web client; redirect URI = `{PUBLIC_BASE_URL}/login/oauth2/code/google` |
| `QR_HMAC_SECRET` | **required**, ≥ 32 chars | rotating QR HMAC key |
| `HASH_PEPPER` | **required**, ≥ 32 chars | pepper for IP/device hashes and the device-cookie signature |
| `COOKIE_SECURE` | `true` | set `false` only for plain-http local dev in browsers other than Chrome |
| `FORWARD_HEADERS_STRATEGY` | `none` | set `framework` behind a TLS-terminating proxy (Cloud Run, Caddy, nginx, tunnels) |
| `SPRING_PROFILES_ACTIVE` | | `local` is used by `./mvnw spring-boot:run` |
| `JOBS_IN_APP_SCHEDULE` | `true` | in-app timers for the retention purge (03:30 Europe/Bucharest) and auto-close (every minute). Set `false` on Cloud Run, where Cloud Scheduler triggers them |
| `JOBS_TRIGGER_SERVICE_ACCOUNT` | *(empty = disabled)* | the Google service account whose OIDC token may call `POST /internal/jobs/daily` (Cloud Scheduler) |
| `JOBS_TRIGGER_AUDIENCE` | `{PUBLIC_BASE_URL}/internal/jobs/daily` | the exact token audience that endpoint accepts |

Other settings have defaults in [`application.yml`](src/main/resources/application.yml) under `attendance.*`: session default and maximum duration (15 min / 3 h), check-in grace (3 min), QR window (15 s), rate limits and retention. Override them with Spring's usual env-var binding, e.g. `ATTENDANCE_SESSION_DEFAULTDURATION=20m`.

**HTTP sessions** (the scan bound to the browser, the OAuth login state, the sign-in itself) are stored in Redis with Spring Session, under `qra:session:sessions:<id>`. Users stay signed in across deploys and restarts, and several app instances share them. The session cookie is `SESSION` (HttpOnly, `Secure` unless `COOKIE_SECURE=false`, SameSite=Lax). The idle timeout is `server.servlet.session.timeout` (60 min), and an open projector page keeps its session alive. If Redis is down, pages that need a session answer 503 "try again in a minute" and the rate limiter lets requests through. A session the app can't read back after an upgrade is discarded, so the user just signs in again.

## Deployment

**How it's deployed today:**
- **Google Cloud Run** in `europe-west1`, scaling from 0 to 1 instance (`--max-instances 1`). HTTP sessions are in Redis, so the maximum can be raised and deploys no longer sign anyone out.
- **Secret Manager** holds the five secrets (`DATABASE_PASSWORD`, `REDIS_URL`, `GOOGLE_CLIENT_SECRET`, `QR_HMAC_SECRET`, `HASH_PEPPER`), mounted as env vars. Everything else is a plain env var on the service.
- The service runs as a **dedicated runtime service account**. Its only permission is read access to those five secrets.
- Postgres is on Neon (Frankfurt) and Redis on Upstash (Belgium). Redis holds the HTTP sessions and the rate-limit counters. One class uses about 1,400 Redis commands, so the free plan's 500K/month covers about 11 classes a day ([details](docs/deployment.md#upstash-command-budget)).

Redeploying is manual until continuous deployment lands (#27). Build an image from a clean export of `master`, then point the service at it. The env vars and secrets stay on the service.

```bash
REGION=europe-west1
IMAGE="$REGION-docker.pkg.dev/$PROJECT/qr-attendance/app:$(git rev-parse --short origin/master)"
# from a clean export of master, e.g. `git archive origin/master | tar -x -C <empty dir>`
gcloud builds submit --region "$REGION" --tag "$IMAGE" .
gcloud run deploy qr-attendance --image "$IMAGE" --region "$REGION"
```

Known gaps: the first request after an idle period waits for a cold start. It used to take ~17 s; the image now ships a JDK 25 AOT cache and starts with C1 only, which cut startup by ~75% in CI (19.5 → 4.2 s on 1 vCPU). In production (1 vCPU, 512 MiB) Spring now starts in ~5.7 s instead of 18–20 s, about 9 s including the JVM ([details](docs/deployment.md#cold-start), #28).

**Scheduled jobs.** Cloud Run scales to zero, so in-app timers can't be relied on there. Instead, once the one-time setup has run, **Cloud Scheduler** calls `POST /internal/jobs/daily` every night at 03:30 Europe/Bucharest. That call runs the GDPR retention purge and the auto-close of expired sessions. The endpoint accepts only a Google-signed OIDC token of one dedicated service account, for that exact URL. On docker compose or a VPS the in-app timers do the same, with no setup. Details, security and the one-time setup script: [docs/deployment.md](docs/deployment.md#scheduled-jobs-cloud-scheduler).

For other hosts (Oracle Cloud Always Free, a cheap EU VPS with Caddy, university hosting) and the GDPR checklist, see [docs/deployment.md](docs/deployment.md).

## Development workflow

- `master` is protected. Every change goes through a pull request (admins included). No approval is needed, but all required checks must be green and the branch must be up to date:
  - build and test (`./mvnw verify` including Testcontainers)
  - Docker image build
  - branch name
  - gitleaks
  - Semgrep
  - CodeQL
- Branches are named `feature/…`, `bugfix/…` or `hotfix/…`.
- PRs are squash-merged, with `gh pr merge <n> --auto --squash` or `python scripts/merge_pr.py <n>`. The script updates a branch that's behind, waits for the checks, then merges.
- Dependabot runs on Mondays. Its grouped Maven minor/patch PR merges itself once the checks pass.

Details in [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

- **Headers** on every response (`SecurityConfig`): a strict CSP (`'self'` only, no inline scripts or styles,
  `frame-ancestors 'none'`), HSTS, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: same-origin` (the scan URL
  carries the QR token), a `Permissions-Policy` that turns off camera, microphone, geolocation and similar features,
  and cross-origin isolation (`COOP same-origin`, `COEP require-corp`, `CORP same-origin`). Pages are `no-store`.
- **Cookies:** `SESSION` and `device_id` are HttpOnly, Secure and SameSite=Lax.
- **On every PR:** gitleaks, Semgrep and CodeQL (required), plus Dependabot alerts and security updates.
- **On the live app:** an OWASP ZAP **passive** baseline scan (`dast.yml`) every Monday and after each deploy. It
  doesn't attack and doesn't sign in, so it's safe while students use the app. Findings go to the job summary, a
  report artifact and one GitHub issue; accepted findings and their reasons are in `.zap/rules.tsv`.

## Roadmap

Tracked as [GitHub issues](https://github.com/SanduCC/qr-attendance/issues). The main open ones:

- **Operations:** #39 trust only the proxy's `X-Forwarded-For` entry for client IPs · #31 custom domain · #1 upgrade to Spring Boot 4.x
- **Features:** #2 Sign in with Apple · #3 optional geofence · #4 Google Workspace domain lock per course · #6 live table via SSE instead of polling · #8 per-student attendance stats · #9 multiple teachers per course
- **Done recently:** #5 teachers can edit student names · #10 manual entry by the teacher
