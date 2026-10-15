# CLAUDE.md — QR Attendance App

## What this app does
A teacher opens an attendance session for a class. A large screen or projector shows a QR code. Students scan it with their phone camera, which opens a web page (no app install). They sign in, confirm their first and last name, and get recorded in that day's attendance table. Each student can check in **only once per session**, and nobody should be able to check in classmates who aren't in the room.

## Tech stack
- **Backend:** Java 25 (Amazon Corretto 25), Spring Boot 3.5.x (upgrade to 4.x planned) (Web, Security, OAuth2 Client, Data JPA, Validation)
- **DB:** PostgreSQL 16, Flyway migrations
- **Cache / short-lived state:** Redis (QR tokens, rate limiting, device locks)
- **Frontend:** server-rendered Thymeleaf + a little vanilla JS. The student page must be mobile-first and load fast on bad Wi-Fi. No SPA framework unless there's a clear need.
- **QR generation:** ZXing (server-side PNG/SVG) or `qrcode` JS on the display page
- **Auth:** Spring Security OAuth2 login with **Google** (required for MVP) and **Apple** (phase 2)
- **Packaging:** Docker + docker-compose (app, postgres, redis)
- **Tests:** JUnit 5, Spring Boot Test, Testcontainers (Postgres + Redis)

## Domain model
```
Teacher(id, email, name NULL, active, added_by NULL, created_at, last_login_at NULL)
Course(id, teacher_id, name, code)
AttendanceSession(id UUID, course_id, opened_at, closes_at, status[OPEN|CLOSED],
                  expected_headcount NULL, geofence_lat NULL, geofence_lng NULL, geofence_radius_m NULL)
Student(id, provider[GOOGLE|APPLE], provider_subject, email, first_name, last_name, created_at)
    UNIQUE(provider, provider_subject)
AttendanceRecord(id, session_id, student_id, checked_in_at, device_id_hash, ip_hash, user_agent)
    UNIQUE(session_id, student_id)       -- one check-in per student per session
    UNIQUE(session_id, device_id_hash)   -- one check-in per device per session
```
- Identify students by `provider_subject` (the OAuth `sub` claim), **never** by email or name.
- Names come pre-filled from the OAuth profile. The student can correct them only on first registration; after that they're locked (teachers can edit).

## Core flows

### Teacher
1. Log in (Google OAuth). The teacher role comes only from the admin-managed teacher list (`/admin/teachers`);
   admins come from `ADMIN_EMAILS` (config only) and are teachers too. Nobody gets a role by signing in.
   Deactivated teachers lose access immediately; their courses and attendance are kept.
2. Create or select a course → "Start session" (default duration 15 min, configurable).
3. Display page (`/sessions/{id}/display`) shows a **rotating QR code** plus a live counter: "23 checked in / expected 25".
4. Live attendance table on the same page (via SSE or 3s polling); the teacher can remove a suspicious entry.
5. Close the session manually or let it expire. Export CSV.

### Student
1. Scan QR → `GET /a/{sessionId}?t={token}`.
2. Server validates the token **before** redirecting to login and stores `sessionId + token` in the HTTP session.
3. OAuth login (Google / Apple).
4. Confirm-name screen (first-time only) → `POST /a/{sessionId}/check-in`.
5. Success screen with name + timestamp. A second attempt shows "Already checked in at 09:02".

## Anti-cheating rules (non-negotiable)
1. **Rotating QR token.** The token is `HMAC-SHA256(sessionId + timeWindow, secret)`, where the window is 15s. Accept the current and previous window only (~30s validity). This stops students from photographing the QR and sending it to friends at home.
2. **Token check happens at scan time.** After OAuth the student has a short grace period (e.g. 3 min) to finish. The token is bound to their HTTP session and can't be reused by another browser.
3. **One check-in per account per session.** Enforce it with a DB unique constraint, not only in app code. Handle `DataIntegrityViolationException` → "already checked in".
4. **One check-in per device per session.** On first visit, set a long-lived, httpOnly, signed `device_id` cookie. Store only its hash. This stops one phone from logging into several friends' Google accounts back to back. (It's not bulletproof against incognito mode; that's what rule 5 is for.)
5. **Headcount sanity check.** If `expected_headcount` is set, the display shows a warning when check-ins exceed it. The teacher can also do a quick visual count.
6. **Optional geofence (phase 2).** Request browser geolocation; reject if outside the radius. Off by default because indoor GPS is unreliable.
7. **Optional domain restriction.** If the school uses Google Workspace, restrict to the `hd` claim (e.g. `@university.ro`). Configurable per course.
8. **Rate-limit** check-in attempts per IP and per device (Redis, e.g. 10/min).

## API / routes
| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/admin/teachers` | admin | list teachers |
| POST | `/admin/teachers` | admin | add a teacher by email (or reactivate) |
| POST | `/admin/teachers/{id}/deactivate` · `/activate` | admin | revoke / restore teacher access |
| GET | `/teacher/courses` | teacher | list courses |
| POST | `/teacher/courses/{id}/sessions` | teacher | open session |
| GET | `/sessions/{id}/display` | teacher | QR + live counter page |
| GET | `/sessions/{id}/qr-token` | teacher | current token (polled every ~10s) |
| GET | `/sessions/{id}/records` | teacher | JSON/SSE list of check-ins |
| DELETE | `/sessions/{id}/records/{recordId}` | teacher | remove entry |
| POST | `/sessions/{id}/close` | teacher | close session |
| GET | `/sessions/{id}/export.csv` | teacher | export |
| GET | `/a/{sessionId}?t=` | public | scan landing, validates token |
| POST | `/a/{sessionId}/check-in` | student | record attendance |

## Security & privacy (GDPR — users are in Romania/EU)
- Store the minimum: name, email, provider subject, check-in time. Hash IP and device ID with a server-side pepper; never store raw values.
- Retention: configurable (default: end of academic year), with a scheduled purge job.
- Show a short privacy notice on the student page before sign-in.
- CSRF on for all form POSTs. Secrets (HMAC key, OAuth client secrets) come only from env vars and are never committed.
- Teachers can only see their own courses. Enforce this in the service layer, not just controllers.

## Android & iOS support
The app is a mobile web app / **PWA**, so a single codebase covers Android and iOS with no app store.
- **Scanning:** Android's built-in camera (or Google Lens) and the iOS camera both open the QR URL directly. Don't build an in-app scanner for the MVP.
- **PWA:** add `manifest.webmanifest` (name, icons 192/512, `display: standalone`, theme color) and a minimal service worker that caches static assets. Chrome on Android then offers "Add to Home screen" / "Install app".
- **Sign-in on Android:** Google OAuth works natively in Chrome. Apple Sign In also works on Android through the web redirect flow, so no extra setup is needed.
- **Browsers to test:** Chrome Android, Samsung Internet, Safari iOS. Also test in-app browsers (e.g. a link opened from WhatsApp/Instagram): Google blocks OAuth in embedded webviews, so detect them and show "Open in Chrome/Safari".
- **Device cookie:** the `device_id` cookie must be `SameSite=Lax; Secure`. On Android, the installed PWA and the browser share cookies; on iOS they don't, which is acceptable.
- **Native app later (optional):** if ever needed, wrap the PWA with a Trusted Web Activity (Android) instead of rewriting it. A Kotlin/React Native app is out of scope.

## Apple Sign In notes
- Requires a paid Apple Developer account, a Services ID, and a private key (.p8) to generate the client secret JWT (it expires within 6 months, so automate regeneration).
- Apple sends the user's name **only on the first authorization**, and as a form POST to the redirect URI. Persist it immediately.
- Users may hide their email (`privaterelay.appleid.com`). That's fine, because we key on `sub`.

## Project structure
```
src/main/java/.../attendance/
  config/        Security, OAuth, Redis, properties
  course/        Course entity, repo, service, controller
  session/       AttendanceSession, QR token service, display controller
  checkin/       Student, AttendanceRecord, check-in service + controller
  export/        CSV export
  common/        errors, device-id filter, rate limiter
src/main/resources/
  db/migration/  Flyway V1__init.sql ...
  templates/     Thymeleaf (teacher/, student/)
```

## Conventions
- Constructor injection, records for DTOs, no Lombok on entities.
- Business rules live in services; controllers stay thin.
- All times stored as `timestamptz` (UTC) and rendered in `Europe/Bucharest`.
- Every anti-cheat rule above gets at least one integration test (expired token, reused device, duplicate account, wrong domain).
- Never trust client-sent names, session IDs, or timestamps without server validation.

## Commands
```
docker compose up -d postgres redis   # deps
./mvnw spring-boot:run                # run app (profile: local)
./mvnw test                           # unit + Testcontainers tests (Testcontainers tests are skipped when Docker isn't running; CI runs them)
./mvnw verify                         # full build
```
Required env: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `QR_HMAC_SECRET`, `HASH_PEPPER`, (phase 2) `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_SERVICES_ID`, `APPLE_PRIVATE_KEY`.

## MVP scope
**In:** Google login, courses, sessions, rotating QR, one-per-account + one-per-device, live teacher table, CSV export.
**Later:** Apple login, geofence, Workspace domain lock, stats per student, multiple teachers per course, offline/manual entry by teacher.

## When working on this repo
- Don't weaken any anti-cheating rule without asking.
- Write a Flyway migration for every schema change; never edit an applied migration.
- Test the student flow on a narrow (375px) viewport.
- Every change goes through a PR to `master` (protected: no direct pushes, 0 approvals, all checks green).
  Branch names: `feature/…` (anything new, incl. CI/docs), `bugfix/…`, `hotfix/…` — see CONTRIBUTING.md.
