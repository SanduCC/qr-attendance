# QR Attendance

[![CI](https://github.com/SanduCC/qr-attendance/actions/workflows/ci.yml/badge.svg)](https://github.com/SanduCC/qr-attendance/actions/workflows/ci.yml)
[![security](https://github.com/SanduCC/qr-attendance/actions/workflows/security.yml/badge.svg)](https://github.com/SanduCC/qr-attendance/actions/workflows/security.yml)

Students scan a rotating QR code shown in class, sign in with Google (Apple later), and get recorded in the day's attendance table — once per student, once per device.

Works on Android and iOS as a mobile web app / PWA, no app store needed.

See [CLAUDE.md](CLAUDE.md) for the full spec, anti-cheating rules and conventions, and [docs/deployment.md](docs/deployment.md) for hosting options.

## Features

- **Teachers** sign in with Google (allowlist), create courses, open a timed session, and project a display page with a rotating QR code, a live "checked in / expected" counter and a live attendance table. They can remove suspicious entries, close the session and export CSV.
- **Students** scan the QR with the phone camera, sign in with Google, confirm their name (first time only) and are checked in. No app install.

### Anti-cheat (summary)

| Rule | How |
|---|---|
| Rotating QR | HMAC-SHA256 token per 15 s window; current + previous window accepted (~30 s) |
| Scan-time validation | Token checked before login, bound to the browser's HTTP session, short grace period to finish sign-in |
| One check-in per account | DB unique constraint `(session_id, student_id)` |
| One check-in per device | Signed httpOnly `device_id` cookie, only its hash stored; DB unique `(session_id, device_id_hash)` |
| Headcount check | Display warns when check-ins exceed the expected headcount |
| Rate limiting | Per IP and per device (Redis) |
| Later | Geofence, Google Workspace domain lock |

## Stack

Amazon Corretto 25 (Java 25) · Spring Boot 3.5 · PostgreSQL 16 + Flyway · Redis 7 · Thymeleaf + vanilla JS · Docker / docker compose · JUnit 5 + Testcontainers

## Quick start (local)

**Prerequisites:** JDK 25 (e.g. [Amazon Corretto 25](https://docs.aws.amazon.com/corretto/)), Docker (for Postgres/Redis and for integration tests), a Google OAuth client (see below).

1. Start the dependencies:

   ```sh
   docker compose up -d postgres redis
   ```

2. Configure the environment. Copy `.env.example` to `.env` and fill in the values (`.env` is gitignored), then load it into your shell — Spring Boot does not read `.env` by itself.

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
   export GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... TEACHER_EMAILS=you@gmail.com
   export QR_HMAC_SECRET="$(openssl rand -base64 48)" HASH_PEPPER="$(openssl rand -base64 48)"
   ```

   ```powershell
   $env:GOOGLE_CLIENT_ID = "..."; $env:GOOGLE_CLIENT_SECRET = "..."; $env:TEACHER_EMAILS = "you@gmail.com"
   $env:QR_HMAC_SECRET = "<random, >= 32 chars>"; $env:HASH_PEPPER = "<random, >= 32 chars>"
   ```

3. Run the app (uses the `local` profile):

   ```sh
   ./mvnw spring-boot:run          # Windows cmd/PowerShell: .\mvnw.cmd spring-boot:run
   ```

4. Open <http://localhost:8080>, sign in with an email listed in `TEACHER_EMAILS`.

To run the whole stack in containers instead: `docker compose up -d --build` (secrets come from `.env`).

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

Quick-tunnel URLs change on every start; a named Cloudflare tunnel (or any tunnel with a fixed hostname) saves re-registering the redirect URI. Test Chrome Android, Samsung Internet and Safari iOS, plus a link opened from an in-app browser (WhatsApp/Instagram) — the app should tell the user to open it in Chrome/Safari.

## Running tests

```sh
./mvnw test       # unit + integration tests
./mvnw verify     # full build
```

Integration tests use Testcontainers (PostgreSQL 16 + Redis 7). They are **skipped automatically when Docker isn't running**; CI (GitHub Actions, see `.github/workflows/ci.yml`) always runs them, and also checks that the Docker image builds.

## Configuration

All configuration comes from environment variables. Secrets are never committed; generate them with e.g. `openssl rand -base64 48`.

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `8080` | HTTP port (Cloud Run sets it) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/attendance` | JDBC URL |
| `DATABASE_USERNAME` | `attendance` | |
| `DATABASE_PASSWORD` | `attendance` | |
| `REDIS_URL` | `redis://localhost:6379` | use `rediss://` for TLS (e.g. Upstash) |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | base URL encoded in the QR code |
| `TEACHER_EMAILS` | *(empty)* | comma-separated allowlist of teacher Google emails |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | **required** | Google OAuth web client; redirect URI = `{PUBLIC_BASE_URL}/login/oauth2/code/google` |
| `QR_HMAC_SECRET` | **required**, ≥ 32 chars | rotating QR HMAC key |
| `HASH_PEPPER` | **required**, ≥ 32 chars | pepper for IP/device hashes and the device-cookie signature |
| `COOKIE_SECURE` | `true` | set `false` only for plain-http local dev in browsers other than Chrome |
| `FORWARD_HEADERS_STRATEGY` | `none` | set `framework` behind a TLS-terminating proxy (Cloud Run, Caddy, nginx, tunnels) |
| `SPRING_PROFILES_ACTIVE` | | `local` is used by `./mvnw spring-boot:run` |

## Deployment

See [docs/deployment.md](docs/deployment.md): Cloud Run + managed Postgres/Redis, Oracle Cloud Always Free, a cheap EU VPS with Caddy, or university hosting — plus a GDPR checklist.

## Contributing

Every change goes through a pull request; CI (build + Testcontainers tests, Docker build) and security checks (gitleaks, Semgrep) must be green before `scripts/merge_pr.py` squash-merges it. Dependabot opens update PRs every Monday. Details in [CONTRIBUTING.md](CONTRIBUTING.md).

## Roadmap

Tracked as [GitHub issues](https://github.com/SanduCC/qr-attendance/issues).


- Sign in with Apple
- Optional geofence
- Google Workspace domain lock per course
- Per-student attendance stats
- Multiple teachers per course
- Manual / offline entry by the teacher
- Upgrade to Spring Boot 4.x
