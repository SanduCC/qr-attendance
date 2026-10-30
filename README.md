# QR Attendance

[![CI](https://github.com/SanduCC/qr-attendance/actions/workflows/ci.yml/badge.svg)](https://github.com/SanduCC/qr-attendance/actions/workflows/ci.yml)
[![security](https://github.com/SanduCC/qr-attendance/actions/workflows/security.yml/badge.svg)](https://github.com/SanduCC/qr-attendance/actions/workflows/security.yml)

Students scan a rotating QR code shown in class, sign in with Google (or, without a Google account, with a one-time code sent to their email address; Apple later), and get recorded in the day's attendance table. Each student can check in once per session, and each phone only once per session.

It runs as a mobile web app / PWA, so it works on Android and iOS with no app store.

**Live app:** <https://qr-attendance-1057355191791.europe-west1.run.app>. It runs on Google Cloud Run (europe-west1, Belgium), and the data stays in the EU: Neon Postgres in Frankfurt and Upstash Redis in Belgium.

See [CLAUDE.md](CLAUDE.md) for the full spec, anti-cheating rules and conventions, and [docs/deployment.md](docs/deployment.md) for hosting options.

## Who does what

| Role | Who | Can |
|---|---|---|
| **Admin** | only the Google accounts listed in `ADMIN_EMAILS` (server config, not editable in the app) | everything a teacher can, plus manage teachers at `/admin/teachers`. Only admins see the **Teachers** tab |
| **Teacher** | a Google account an admin added at `/admin/teachers` | create courses, run attendance sessions, export attendance. Teachers see only their own courses |
| **Student** | anyone with a Google account who scans a live QR code, or anyone with an email address when [email sign-in](#email-sign-in-optional) is on | check in to that session |

- Nobody becomes a teacher just by signing in. An admin adds the teacher's Google email first. Teachers and admins always sign in with Google; an email-code sign-in is a student, even for a teacher's or admin's address.
- Admins can **deactivate** a teacher, and access stops immediately: every teacher request checks the database, not only the role given at sign-in. The teacher's courses and attendance are kept. **Reactivating** them, or adding the same email again, restores the same account. Admins can't be deactivated from the UI.

## Teacher flow

1. **My courses** (`/teacher/courses`) is the home page and shows one card per course. Each card shows the course code (if any) and the last session's date and count, e.g. "23 of 25 checked in".
2. **Start attendance** is one click. It opens a 15-minute session (configurable) and reuses the expected headcount from the course's last session. The **Options** panel lets you set a different duration or expected headcount, or tick **Require location** (see [Location check](#location-check-optional)). The projector page opens straight away.
3. **New course** only needs a name. The course code is optional, and so is the **Google Workspace domain lock** (see [Domain lock](#domain-lock-optional)).
4. **History & exports** (`/teacher/courses/{id}`) lists every session of the course with its time window, Open/Closed status, count, and **Display / Excel / CSV** links. **Course settings** on the same page edits the name, code and domain lock. **Stats** shows each student's attendance across the course (see [Attendance per student](#attendance-per-student)).
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

### Attendance per student

**Stats** (`/teacher/courses/{id}/stats`, linked from the course card and the course history) shows one row per person
who attended at least one session of the course: name, email, **sessions attended / total**, the percentage and the
date of the last session attended, in Romanian alphabetical order. **Download Excel / CSV**
(`/teacher/courses/{id}/stats.xlsx`, `stats.csv`) have the same conventions as the session exports (title block,
frozen and filterable header, A4 landscape; `;` + UTF-8 BOM CSV), with the columns
**Nr. · Last name · First name · Email · Attended · Sessions · Percentage · Last attended · Source**. Files are named
`<course code or name>_stats_<today>`.

How it counts:

- **Total** is every session of the course, open and closed, also sessions nobody checked in to (the page says how
  many). Sessions removed by the retention purge are gone from the total and from everyone's count.
- A session counts for a student when they have an entry in it: a scan, or a manual entry the teacher added for them.
- Manual entries for someone **without an account** are listed under the typed name, marked **manual** (Source
  `manual`; `account` for students). The same name (ignoring case) in several sessions counts as one person; a student
  with an account and the same name stays a separate row.
- **Last attended** is the date of the session, not when an entry was added (a manual entry can be added days later).
- Percentages are rounded down, so 99.5% shows as 99%, never 100%.

### Domain lock (optional)

A course can accept only Google Workspace accounts of your school, e.g. `university.ro` (up to 5 domains, comma-separated; set when creating the course or later under **Course settings**). Empty means any Google account.

- The check uses the **`hd` claim of the Google ID token**, which Google sets only for Workspace accounts. It never trusts the email address alone, so personal Gmail accounts are refused. Case doesn't matter, and a subdomain counts as a different domain.
- It runs at check-in, before anything is written, and the student sees "This course only accepts @university.ro accounts" with a **Choose another Google account** button. Switching accounts keeps the scan, so they can still finish within the 3-minute grace period.
- For a locked course the sign-in link also asks Google for that domain (`hd=…`, `prompt=select_account`). That's only a convenience: the server-side check is what counts.
- With [email sign-in](#email-sign-in-optional), the proof is the domain of the address the student received the code at: it must be exactly one of the course's domains (any case; a subdomain is a different domain). A wrong address gets no code when the student came from a locked course's QR code, and the check-in refuses it in any case.

### Location check (optional)

Off by default, because indoor GPS is unreliable. Tick **Require location** under **Options** when starting a session from a phone or laptop **in the classroom**: that device's position (browser geolocation) becomes the centre, with a radius of 100 m by default (25–1000 m).

- Students on such a session are asked for their location when they tap **Check in**. The server computes the distance (haversine) and accepts when `distance − min(reported accuracy, 100 m) ≤ radius`: a phone that honestly reports ±60 m gets those 60 m, but claiming a huge inaccuracy can't stretch the fence by more than 100 m. A missing or unusable location is refused.
- Permission denied, position unavailable and timeouts each get a clear message, and the student can retry within the grace period.
- **Nothing about the student's location is stored**: the position is compared once and discarded. Only the session's centre and radius are kept.
- Geolocation needs HTTPS (production has it). The location comes from the student's browser and **can be faked**, so it's a deterrent against checking in from home, not proof of presence. The headcount check and a look around the room still matter.

## Student flow

1. Scan the QR code with the phone camera. This opens `/a/{sessionId}?t=…`, and the server checks the token **before** sign-in.
2. A short privacy notice is shown (it says which school account to use when the course has a domain lock, and mentions the location check when it's on), then **Continue with Google**. In-app browsers (WhatsApp, Instagram, …) can't do Google sign-in, so the page detects them and asks the student to open the link in Chrome or Safari instead. When email sign-in is on, there is also **No Google account? Sign in with email** (it works in in-app browsers too).
3. The first time only, the student confirms their first and last name, which are pre-filled from Google (typed by the student after an email sign-in). After that the names are locked; only a teacher can correct them. On a session with a location check, tapping **Check in** asks for the phone's location first.
4. A success screen shows the student's name and check-in time. Scanning again shows "Already checked in at 09:02".

### Email sign-in (optional)

For students without a Google account. **Off by default**; it needs an SMTP provider ([setup](docs/deployment.md#email-sign-in-smtp)).

1. **Sign in with email** on the scan landing or the sign-in page, then enter the address.
2. A 6-digit code arrives by email (it's in the subject too, so it shows in the phone's notification).
3. The student types it **on the same page, in the same browser**, then confirms their name and checks in as usual. It's a code and not a link because a link would open in the mail app's browser and lose the scan, which is bound to this browser.

- Codes are random, valid for 10 minutes, single use, and invalid after 5 wrong tries. They only work in the browser session that asked for them. Redis keeps only a peppered hash of a code, for at most 10 minutes, and the code is never written to the database or the logs.
- Limits: 3 codes per address per 15 min, 5 per device per 15 min, 60 per IP per hour, 250 per day app-wide, and 10 code entries per browser per 15 min. The page never says whether an address already has an account.
- The account is the address, trimmed and lowercased (dots and `+tags` kept). A Google account and an email sign-in with the same address are two different students.
- The scan rules don't change: the QR token is still checked at scan time, the 3-minute grace period still applies, and so do one check-in per account and per phone, the location check and the locked names.

### Anti-cheat (summary)

| Rule | How |
|---|---|
| Rotating QR | HMAC-SHA256 token per 15 s window; current + previous window accepted (~30 s) |
| Scan-time validation | Token checked before login and bound to the browser's HTTP session (and device cookie); 3 min grace period to finish sign-in |
| One check-in per account | DB unique constraint `(session_id, student_id)`; students are keyed on provider + subject (the Google `sub`, or the verified address for email sign-in), never on name |
| One check-in per device | Signed httpOnly `device_id` cookie, only its hash stored; DB unique `(session_id, device_id_hash)` |
| Headcount check | Display warns when check-ins exceed the expected headcount (manual entries count too) |
| Manual entries | Teacher-only, marked `manual` with the teacher's id; no device/IP hash, so they never block or weaken a real scan. A DB check keeps every real scan's device and IP hash mandatory |
| Rate limiting | Per IP (120/min, since a whole class can share one Wi-Fi IP) and per device (10/min), in Redis |
| Location check (optional, off by default) | Browser geolocation vs the classroom centre captured at session start; `distance − min(accuracy, 100 m) ≤ radius`; nothing stored; a deterrent, not proof |
| Domain lock (optional, per course) | The Google ID token's `hd` claim must be one of the course's Workspace domains; consumer Gmail has no `hd`. For email sign-in: the verified address's domain |
| Email sign-in (optional, off by default) | 6-digit code, 10 min, single use, 5 tries, bound to the browser session, hashed in Redis only; send and entry rate limits; never grants teacher or admin |

IP addresses and device IDs are stored only as peppered hashes. Attendance data is purged after the academic year ends (default 30 Sep plus 30 days' grace).

## Stack

Amazon Corretto 25 (Java 25) · Spring Boot 4.1 (Spring Framework 7, Spring Security 7, Hibernate 7) · PostgreSQL 16 + Flyway · Redis 7 (HTTP sessions via Spring Session, rate limiting) · Thymeleaf + vanilla JS · Docker / docker compose · JUnit 6 + Testcontainers

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
| `TRUSTED_PROXY_HOPS` | `1` | with `framework` only: proxies in front that append to `X-Forwarded-For`; the client IP (per-IP rate limit, `ip_hash`) is that many entries from the right, never a value the client sent. `1` = Cloud Run `run.app`, Caddy, nginx; `2` = behind an external load balancer; `0` = TCP peer ([details](docs/deployment.md#client-ip-behind-a-proxy)) |
| `SPRING_PROFILES_ACTIVE` | | `local` is used by `./mvnw spring-boot:run` |
| `JOBS_IN_APP_SCHEDULE` | `true` | in-app timers for the retention purge (03:30 Europe/Bucharest) and auto-close (every minute). Set `false` on Cloud Run, where Cloud Scheduler triggers them |
| `JOBS_TRIGGER_SERVICE_ACCOUNT` | *(empty = disabled)* | the Google service account whose OIDC token may call `POST /internal/jobs/daily` (Cloud Scheduler) |
| `JOBS_TRIGGER_AUDIENCE` | `{PUBLIC_BASE_URL}/internal/jobs/daily` | the exact token audience that endpoint accepts |
| `EMAIL_SIGN_IN_ENABLED` | `false` | turns on [email sign-in](#email-sign-in-optional). The app then refuses to start unless `MAIL_HOST` and `MAIL_FROM` are set |
| `MAIL_HOST` / `MAIL_PORT` | *(empty)* / `587` | SMTP server, e.g. `smtp-relay.brevo.com`. Port 465 means SMTPS; any other port requires STARTTLS. Plain-text SMTP is never used |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | *(empty)* | SMTP login (`MAIL_PASSWORD` is a secret: the provider's SMTP key, not an account password) |
| `MAIL_FROM` | *(empty)* | sender, e.g. `QR Attendance <attendance@example.ro>`; an address the provider has verified |

Other settings have defaults in [`application.yml`](src/main/resources/application.yml) under `attendance.*`: session default and maximum duration (15 min / 3 h), check-in grace (3 min), QR window (15 s), rate limits, retention, the email sign-in (`attendance.email-sign-in.*`: code validity, tries, resend cooldown, send timeout and limits), and the location check (`attendance.geofence.default-radius-m` 100, `min-radius-m` 25, `max-radius-m` 1000, `max-accuracy-credit-m` 100). Override them with Spring's usual env-var binding, e.g. `ATTENDANCE_SESSION_DEFAULTDURATION=20m`.

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
- PRs are squash-merged with `python scripts/merge_pr.py <n>` (or `gh pr merge <n> --auto --squash`, only for your own PRs). The script updates a branch that's behind, waits for the checks, then merges. PRs from forks or other authors, and PRs touching CI, build or test files, are guarded: green checks don't count (a PR can rewrite its own checks), so they merge only after the maintainer read the diff and passes `--reviewed-sha <head sha>`.
- Dependabot runs on Mondays. Its grouped Maven minor/patch PR merges itself once the checks pass.

Details in [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

- **Headers** on every response (`SecurityConfig`): a strict CSP (`'self'` only, no inline scripts or styles,
  `frame-ancestors 'none'`), HSTS, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: same-origin` (the scan URL
  carries the QR token), a `Permissions-Policy` that turns off camera, microphone and similar features
  (geolocation is `(self)` only, for the optional location check),
  and cross-origin isolation (`COOP same-origin`, `COEP require-corp`, `CORP same-origin`). Pages are `no-store`.
- **Cookies:** `SESSION` and `device_id` are HttpOnly, Secure and SameSite=Lax.
- **On every PR:** gitleaks, Semgrep and CodeQL (required), plus Dependabot alerts and security updates.
- **On the live app:** an OWASP ZAP **passive** baseline scan (`dast.yml`) every Monday and after each deploy. It
  doesn't attack and doesn't sign in, so it's safe while students use the app. Findings go to the job summary, a
  report artifact and one GitHub issue; accepted findings and their reasons are in `.zap/rules.tsv`.

## Roadmap

Tracked as [GitHub issues](https://github.com/SanduCC/qr-attendance/issues). The main open ones:

- **Operations:** #31 custom domain
- **Features:** #2 Sign in with Apple · #6 live table via SSE instead of polling · #9 multiple teachers per course
- **Done recently:** #52 email sign-in for students without a Google account · #1 upgrade to Spring Boot 4.1 · #8 per-student attendance stats per course, exportable · #5 teachers can edit student names · #10 manual entry by the teacher · #3 optional geofence · #4 Google Workspace domain lock per course
