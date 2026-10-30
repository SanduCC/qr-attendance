# Deployment

The app is a single container (see the `Dockerfile`) plus PostgreSQL 16 and Redis 7. Any host that can run a container and reach a Postgres and a Redis works. This page compares realistic options for a school / single-teacher setup in the EU (users are in Romania).

> **Free tiers change often.** Every limit, price and policy below is a snapshot to guide the choice — check the provider's current terms before you rely on them for a real class.

| Option | Cost | Always on | Effort | Best for |
|---|---|---|---|---|
| (a) Cloud Run + Neon/Supabase + Upstash | free tier | no (scales to zero, cold starts) | low | trying it out, low traffic |
| (b) Oracle Cloud Always Free VM | free | yes | medium (you run the server) | most free capacity |
| (c) EU VPS (e.g. Hetzner) + Caddy | ~€4–5/month | yes | medium | predictable production |
| (d) University / school IT | usually free to you | yes | depends on IT | GDPR simplicity |
| (e) Render / Railway / Fly / Koyeb | free = sleep or trial | no | low | demos only |

Whatever you pick, the app needs these settings in production (full list in the [README](../README.md#configuration)):

- `PUBLIC_BASE_URL` = the public `https://` URL (encoded in the QR code).
- `FORWARD_HEADERS_STRATEGY=framework` whenever TLS is terminated by a proxy (Cloud Run, Caddy, nginx).
- `TRUSTED_PROXY_HOPS` (default `1`) = how many proxies in front of the app append to `X-Forwarded-For`. `1` fits
  Cloud Run's `run.app` URL, Caddy and nginx; see [Client IP behind a proxy](#client-ip-behind-a-proxy).
- `COOKIE_SECURE=true` (the default).
- Secrets (`GOOGLE_CLIENT_SECRET`, `QR_HMAC_SECRET`, `HASH_PEPPER`, `DATABASE_PASSWORD`, and `MAIL_PASSWORD` if
  [email sign-in](#email-sign-in-smtp) is on) from a secret manager or a root-only `.env`, never committed.
- In the Google OAuth client, add `{PUBLIC_BASE_URL}/login/oauth2/code/google` as an authorized redirect URI.

---

## (a) Google Cloud Run + Neon (or Supabase) Postgres + Upstash Redis

Serverless container plus managed free-tier databases, all in EU regions.

- **Cloud Run** region: `europe-central2` (Warsaw, closest to Romania) or `europe-west1` (Belgium). Pricing tiers differ per region — check which regions the free tier covers.
- **Postgres:** [Neon](https://neon.tech) (e.g. AWS Frankfurt) or [Supabase](https://supabase.com) (e.g. Frankfurt). Use the *direct* (non-pooled) connection string, with `sslmode=require`, e.g. `DATABASE_URL=jdbc:postgresql://ep-xxxx.eu-central-1.aws.neon.tech/attendance?sslmode=require`. Free databases suspend when idle; the first query after a pause is slower.
- **Redis:** [Upstash](https://upstash.com) in an EU region, TLS URL: `REDIS_URL=rediss://default:<password>@<name>.upstash.io:6379`. Redis is **required**: it holds the HTTP sessions (Spring Session, keys `qra:session:sessions:<id>`) as well as the rate-limit counters. Session keys use a TTL and no keyspace notifications, so no `CONFIG` command is needed (Upstash doesn't allow it). See [Upstash command budget](#upstash-command-budget) below.

### Caveats

- **Cold starts.** With zero instances running, the first request starts the JVM and Spring Boot. In practice the teacher opening the display page warms the instance before students scan. What the image does to keep it short, how to measure it and the paid options: [Cold start](#cold-start).
- **Instances.** HTTP sessions (the scanned QR token bound to the browser, the OAuth login state, the sign-in) live in Redis, so `--max-instances` can be raised above 1, and a deploy doesn't sign anyone out (the projector page keeps rotating its QR code). The in-app scheduled jobs (if enabled) then run on every instance. Both are idempotent: auto-close is a single `UPDATE … WHERE status = OPEN AND closes_at <= now`, and the retention purge is a pair of `DELETE … WHERE` statements with a fixed cutoff, so a second run changes nothing. The Upstash command budget is per database, not per instance.
- **Redis down.** Pages that need a session (scan, sign-in, check-in, teacher pages) answer **503 "temporarily unavailable, try again in a minute"** with `Retry-After`. They never continue without the session. The rate limiter fails open. `/actuator/health` reports DOWN (503) while Redis is unreachable. That's accurate, but don't use it as a liveness probe: restarting the app doesn't fix Redis.
- **Background work.** With request-based billing, CPU is throttled outside requests and the instance may be scaled to zero, so in-app timers only fire while an instance happens to be alive, and for the GDPR retention purge the next active day may be weeks away. On Cloud Run, Cloud Scheduler therefore triggers the jobs over HTTPS and the in-app timers are off: see [Scheduled jobs](#scheduled-jobs-cloud-scheduler).
- **Live updates.** The projector page polls every 3 s, so Cloud Run's request timeout (default 5 min, max 60 min) doesn't affect it. If the live table moves to SSE (#6), clients will have to reconnect after each timeout.
- `FORWARD_HEADERS_STRATEGY=framework` is required (Cloud Run terminates TLS). Leave `TRUSTED_PROXY_HOPS` at its
  default `1` for the `run.app` URL or a Cloud Run domain mapping; set `2` only if you put an external Application
  Load Balancer in front ([Client IP behind a proxy](#client-ip-behind-a-proxy)).
- `PUBLIC_BASE_URL` = the `https://<service>-<hash>.<region>.run.app` URL or your custom domain.

### Client IP behind a proxy

The per-IP rate limits (120/min for scan and check-in, 10/min for `/internal/jobs/daily`) and
`attendance_record.ip_hash` use the client's IP address. Behind a proxy the app only sees the proxy's address, so the
client's comes from a header, and that header must be one the client can't write (#39).

**What the proxies send.** Proxies *append* to `X-Forwarded-For`: whatever the client sent stays at the left, and
the proxy adds the address it received the request from at the right. Google documents this for its load balancer:
`X-Forwarded-For: <supplied-value>,<client-ip>,<load-balancer-ip>`, and "the load balancer does not verify any IP
addresses that precede `<client-ip>,<load-balancer-ip>` in this header"
([External Application Load Balancer overview](https://cloud.google.com/load-balancing/docs/https)).
Cloud Run's own docs ([request headers](https://cloud.google.com/functions/docs/reference/headers),
[container contract](https://cloud.google.com/run/docs/container-contract)) don't say how its front end treats a
client's header on the `run.app` URL, so it was measured on production (2026-10-17, `/internal/jobs/daily`, its
10/min limit as the probe):

| Request | Result with Spring's plain `ForwardedHeaderFilter` |
|---|---|
| 11 calls, no extra header | 10 × 401, then 429 |
| then: a new `X-Forwarded-For: 203.0.113.x` per call | 429: limited (so Cloud Run also sends a `Forwarded: for=` header, which Spring reads first) |
| then: a new `Forwarded: for=203.0.113.x` per call | **401: a fresh budget per call**, the limit is bypassed |
| `Forwarded: by=foo` (no `for=`) + a new `X-Forwarded-For` per call, after exhausting that key | **401**: Spring falls back to the left-most `X-Forwarded-For`, which is the client's |

So the client's `Forwarded` and `X-Forwarded-For` values both reach the app in front of Cloud Run's, and Spring
(`ForwardedHeaderUtils.parseForwardedFor`) takes the *left-most* `Forwarded: for=`, else the left-most
`X-Forwarded-For`. Tomcat's `RemoteIpValve` (`server.forward-headers-strategy=native`) would walk `X-Forwarded-For`
from the right, but only when the TCP peer matches its `internal-proxies` pattern. Cloud Run doesn't document the
address the container sees as its peer, and a wrong guess would silently give every client the proxy's address (and
`http` redirect URIs), so the app counts hops instead.

**What the app does.** With `FORWARD_HEADERS_STRATEGY=framework` it registers `TrustedProxyForwardedHeaderFilter` in
place of Spring Boot's `ForwardedHeaderFilter`. Scheme, host and port are handled by Spring's code exactly as before
(OAuth redirect URI `https://…run.app/login/oauth2/code/google`, `request.isSecure()`, HSTS), and the forwarded headers
are still hidden from the rest of the app. Only the client IP differs: it's the `X-Forwarded-For` entry
`TRUSTED_PROXY_HOPS` (`attendance.security.trusted-proxy-hops`) from the right. All `X-Forwarded-For` lines count as
one list; the `Forwarded` header is never used for the IP; an entry that isn't an IP address falls back to the TCP
peer.

| `TRUSTED_PROXY_HOPS` | Use it for | Client IP |
|---|---|---|
| `1` (default) | Cloud Run `run.app` / domain mapping, Caddy, nginx (`$proxy_add_x_forwarded_for`) | right-most entry |
| `2` | an external Application Load Balancer in front of Cloud Run (`<client-ip>,<load-balancer-ip>`) | second from the right |
| `0` | ignore the header | TCP peer |

With fewer entries than hops, every entry came from a trusted proxy, so the left-most is used. A hop count only
holds if clients can't skip a proxy: with a load balancer in front, set Cloud Run's ingress to "internal and Cloud Load
Balancing", or a direct `run.app` call would get its client-written entry counted as the client. With
`FORWARD_HEADERS_STRATEGY=none` (docker compose without a proxy) no header is read at all and the setting does nothing.

**Checking it.** At `DEBUG` the filter logs one line per request with the shape of the headers, never an address:
`Client IP from 2 X-Forwarded-For entries on 1 line(s), 1 trusted hop(s); Forwarded has 2 for= entries, its trusted
entry matches`. "matches" means Cloud Run's own `Forwarded` header names the same client, i.e. the hop count is right.
It's off by default; to look once, set
`LOGGING_LEVEL_IO_GITHUB_SANDUCC_ATTENDANCE_CLIENTIP=DEBUG` on the service (a new revision), make a
request, read the log, then remove the variable again.

### Cold start

With min-instances 0, the first request after about 15 idle minutes waits for a new instance. Before #28 that took
**~17–19 s** until Spring had started (`Started AttendanceApplication in 16.8–20.2 seconds`, one start at 27 s), and
the request that caused it got its page after 22–31 s. Where the time went (production logs of revisions
`00002`–`00004`, 512 MiB, 1 vCPU, startup CPU boost):

| Phase | Time | Bound by |
|---|---|---|
| Instance start → JVM process | 0.4–0.7 s | Cloud Run |
| JVM start, Spring Boot's fat-jar launcher | 2.1–2.5 s | CPU (class loading) |
| `SpringApplication.run` → "Starting …" (config files, logging) | ~1.5 s | CPU |
| Bean definitions: component scan, auto-configuration conditions | 3.6–4.0 s | CPU |
| Repositories, Tomcat initialised, first beans | 2.6–3.1 s (once 6.8 s) | CPU |
| First Postgres connection (Neon, Frankfurt, from Belgium, TLS) | 1.4–1.6 s, 3.3 s when Neon was asleep | network + Neon wake-up |
| Flyway: validate V1–V3, nothing to migrate | 0.6 s | network |
| Hibernate: metamodel, `ddl-auto: validate` | 2.8–4.1 s | CPU, some round trips |
| JPA repositories, Security, MVC, Spring Session, actuator | 3.6–5.4 s | CPU |
| Tomcat start (port open, startup probe passes) | ~1 s | CPU |
| First request: DispatcherServlet, first template, first Redis connection | 0.5–1.9 s | CPU + network |

About 14 of the 17 s are CPU work on one vCPU, mostly loading and linking classes, so that is what the image now
attacks:

- **JDK 25 AOT cache** ([JEP 483](https://openjdk.org/jeps/483)/[514](https://openjdk.org/jeps/514)/[515](https://openjdk.org/jeps/515)).
  The `Dockerfile` extracts the Spring Boot jar (`app.jar` + `lib/`) and runs the app once at build time until the
  Spring context is refreshed (`-Dspring.context.exit=onRefresh`), with `-XX:AOTCacheOutput=/app/app.aot`. That
  training run needs no database or Redis: Flyway, schema validation and the database warm-up are off, and Hibernate
  gets its dialect from a flag instead of asking the database. Production starts with `-XX:AOTCache=/app/app.aot`
  and maps the classes already loaded and linked. The image grows by ~130 MB (~31 MB compressed).
- **C1 only** (`-XX:TieredStopAtLevel=1`): C2's compilations during startup compete with the app for the one vCPU.
  Peak throughput is lower, which doesn't matter at a few requests per second.
- **Database warm-up** (`DatabaseWarmUp`): a background thread opens and closes one Postgres connection as soon as the
  configuration is read, so a sleeping Neon compute wakes up while Spring is still busy with the CPU work above,
  instead of while Flyway waits. It logs `Database warm-up: connected in N ms`. Off with
  `ATTENDANCE_STARTUP_WARMUPDATABASE=false`.
- All JVM flags are in `/app/jvm.options` (`java @/app/jvm.options …`), shared by the training run and production,
  because the cache only works with matching flags, the same JDK and the same jar files.
- `-XX:InitialRAMPercentage=25` is required, not tuning: the cache's archived heap objects (~6 MB) are loaded into
  the old generation at JVM start, and with the default initial heap (8 MB at 512 MiB) they don't fit. The JVM then
  **refuses to start** (`archived heap objects cannot be loaded … unrecoverable error`) rather than running without
  the cache. So the image build starts the app a second time with production's flags, `-XX:MaxRAM=512m` and
  `-XX:AOTMode=on` (any cache problem is fatal), and `docker build` fails if the cache isn't usable. A cache that
  doesn't match in a less severe way (e.g. a changed jar) only logs `[error][aot] …` and the app starts without it.

**Measured** by the [`startup benchmark`](../.github/workflows/startup-benchmark.yml) workflow (informational, not a
required check), which runs on PRs that touch the `Dockerfile`, `pom.xml` or `src/main`. It builds the image of the PR
and of its base commit and starts each a few times against Postgres 16 + Redis 7 with `--cpus 1|2 --memory 512m`.
Run it locally with [`scripts/startup-benchmark.sh`](../scripts/startup-benchmark.sh). Medians on a GitHub runner
(PR #38):

| 512 MiB | `Started … in` 1 vCPU | 2 vCPU | `docker run` → health UP, 1 vCPU | 2 vCPU | first `GET /` (1 vCPU) | memory |
|---|---|---|---|---|---|---|
| before (#28) | 19.5 s | 9.3 s | 22.3 s | 11.0 s | 0.78 s | 282 MiB |
| now | **4.2 s** | **2.7 s** | **5.2 s** | **3.5 s** | 0.36 s | 241 MiB |
| now, cache off (`-XX:AOTMode=off`) | 7.1 s | 5.4 s | 8.3 s | 6.4 s | 0.28 s | 258 MiB |

Step by step at 2 vCPU (from the PR's first benchmark runs): extracted jar and larger initial heap 9.0 → 7.5 s, C1
only → 5.4 s, AOT cache → 2.7 s (the cache with C2: 3.9 s). A GitHub runner has no image pull and no cross-region database, so compare the rows with each other: production's 17–19 s before
look like the 1 vCPU column plus ~2–3 s of network. Tried and dropped, no difference: Hikari `minimum-idle: 2`
(3.8 vs 3.9 s) and 4 vCPU instead of 2.

**Checking production.** After a deploy, in the revision's logs:

```powershell
$gcloud = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud logging read 'resource.type=cloud_run_revision AND resource.labels.service_name=qr-attendance AND (textPayload:\"Started AttendanceApplication\" OR textPayload:\"Starting new instance\" OR textPayload:\"warm-up\" OR textPayload:\"[aot]\")' `
    --project qr-attendance-sanducc --freshness 1d --limit 20 --format 'value(timestamp,resource.labels.revision_name,textPayload)'
```

`Started AttendanceApplication in X seconds (process running for Y)` is the number to compare, and no line may
contain `[aot]`: any `[error][aot]` means the cache wasn't used.

**Rejected:**
- *Spring AOT* (`spring-boot:process-aot`, `-Dspring.aot.enabled=true`): it evaluates `@ConditionalOnProperty` at build
  time, so properties set on the service would stop switching beans, e.g. `FORWARD_HEADERS_STRATEGY=framework` (the
  forwarded-headers filter; without it, OAuth redirect URIs become `http://` and every client has Cloud Run's IP for
  the rate limiter) and `JOBS_IN_APP_SCHEDULE`. Its gain on the JVM is small next to the AOT cache.
- *`spring.main.lazy-initialization`*: moves the same work into the first requests, the ones students are waiting on.
- *`ddl-auto: none`* (skip Hibernate's schema validation): saves a few round trips, but validation is the check that
  a revision matches the migrated schema (see [migrations](#migrations-must-be-backward-compatible)).
- *Moving Cloud Run next to Neon* (`europe-west3`, Frankfurt): saves maybe 0.1–0.3 s of round trips per cold start,
  at the cost of a pricier region and a new service.

**Options that cost money or need GCP changes** (not applied; prices for `europe-west1`, request-based billing,
checked 2026-10; recheck before relying on them):

| Option | Effect | Cost |
|---|---|---|
| Cloud Scheduler pings `/actuator/health` a few minutes before each class | the teacher never sees a cold start | $0 (job 2 of the 3 free per billing account; each ping is one cold start, ~10–20 vCPU-s) |
| Min 1 instance only in class hours (two Scheduler jobs that set the service-level minimum to 1 at 07:45 and back to 0 at 20:00, Mon–Fri) | no cold starts in those hours | idle instance ~$0.0000025/vCPU-s + ~$0.0000025/GiB-s: 12 h × 22 days ≈ 950,000 s ≈ **$2.40 CPU + $1.20 memory ≈ $3.60/month**, before any free-tier credit |
| Min 1 instance around the clock | no cold starts | ≈ **$9.70/month** |
| `--cpu 2` (the boost then adds more) | CI: 1 → 2 vCPU halves the start, 2 → 4 adds nothing. Production's 1 vCPU + boost behaves like ~1 runner vCPU, so it may save a few more seconds | CPU-seconds while serving ×2; at this app's usage still far inside the free 180,000 vCPU-s a month |

The pre-warm ping (commands for the owner, PowerShell):

```powershell
$gcloud = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud scheduler jobs create http qr-attendance-prewarm --project qr-attendance-sanducc --location europe-west1 `
    --schedule '50 7-18 * * 1-5' --time-zone 'Europe/Bucharest' `
    --uri 'https://qr-attendance-1057355191791.europe-west1.run.app/actuator/health' --http-method GET `
    --attempt-deadline 60s --description 'Wake the instance before classes (issue 28)'
```

Narrow `--schedule` to the real timetable (e.g. `'50 7,9,11 * * 1-5'`): every ping wakes Neon for ~5 minutes too.

### Upstash command budget

Upstash free plan, checked 2026-10-16 (it changes, so recheck): **500K commands/month**, 256 MB data, 10 GB bandwidth/month, 10 MB max request, 10K commands/s, 1 database. `INFO`, `PING`, `AUTH` and `HELLO` aren't billed, so health checks and connection setup are free.

The table estimates one class: a 15-minute session with 30 students. The projector page polls the records every 3 s and the QR token about twice per 15 s window.

| Who | Redis commands |
|---|---|
| Projector page: ~420 polls, each one HGETALL; the session's expiry is refreshed at most once a minute (3 commands) | ~465 |
| Teacher: sign in, start, close, export | ~40 |
| Each student: scan (new session) 2, Google redirect 4, callback 5, confirm 4, check-in 4, done 4, rate limiter ~6 | ~29 × 30 = ~870 |
| **Per class** | **~1,400** |

At 4 classes a day for 30 days, that's about **168K commands a month, 34% of the free plan**. The plan covers about 350 classes a month (about 11 a day).

Two settings keep this low:
- The session is written only at the end of a request (flush on save), and only for attributes that changed.
- Requests that change nothing, like the projector's polling, don't rewrite the session.

With Spring Session's defaults, every poll would cost 4 commands and a class about 2,600. Data and bandwidth are small: a session is a few KB with a 60-minute TTL, and polling reads about 5 MB per class.

Bots and scanners probing the public URL (`/wp-login.php`, `/.env`, …) cost nothing: an anonymous GET is redirected
to `/login` without a session. Only the app's own signed-in pages (`/teacher/courses/**`, `/admin/teachers/**`,
`/sessions/{id}/**`, `/a/{id}/**`) save the request in a new session (2 commands), so the user gets back there after
signing in; a valid QR scan and "Sign in with Google" create the sessions the flow needs (#44). An anonymous POST
(PUT, DELETE, …), such as a bot's `POST /xmlrpc.php`, gets CSRF's 403 without a session either: a CSRF token is never
stored in a session the rejected request would have created (#47).

The ZAP baseline scan (`dast.yml`: weekly and after each deploy) is passive and small: ~15-25 GET requests,
~10-20 Redis commands (the scan landing's rate-limit counters) and at most one cold start. That's about 100 commands
a month.

### Setup

```bash
PROJECT=my-gcp-project
REGION=europe-central2            # or europe-west1
gcloud config set project "$PROJECT"
gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com secretmanager.googleapis.com iam.googleapis.com

# Secrets in Secret Manager (stored in the EU region only)
for s in qr-hmac-secret hash-pepper; do
  openssl rand -base64 48 | tr -d '\n' | gcloud secrets create "$s" \
    --replication-policy=user-managed --locations="$REGION" --data-file=-
done
printf '%s' 'GOOGLE-CLIENT-SECRET'  | gcloud secrets create google-client-secret \
  --replication-policy=user-managed --locations="$REGION" --data-file=-
printf '%s' 'NEON-DB-PASSWORD'      | gcloud secrets create database-password \
  --replication-policy=user-managed --locations="$REGION" --data-file=-
printf '%s' 'rediss://default:PASSWORD@NAME.upstash.io:6379' | gcloud secrets create redis-url \
  --replication-policy=user-managed --locations="$REGION" --data-file=-

# A dedicated runtime service account that can read these five secrets and nothing else
# (the default compute service account has broad project roles)
gcloud iam service-accounts create qr-attendance-run --display-name="qr-attendance runtime"
SA="qr-attendance-run@$PROJECT.iam.gserviceaccount.com"
for s in qr-hmac-secret hash-pepper google-client-secret database-password redis-url; do
  gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:$SA" \
    --role=roles/secretmanager.secretAccessor
done
```

Non-secret settings go in an env file (YAML avoids escaping the commas in `ADMIN_EMAILS`):

```yaml
# cloudrun.env.yaml — no secrets in here
DATABASE_URL: "jdbc:postgresql://ep-xxxx.eu-central-1.aws.neon.tech/attendance?sslmode=require"
DATABASE_USERNAME: "attendance"
PUBLIC_BASE_URL: "https://attendance.example.ro"
ADMIN_EMAILS: "ana.popescu@example.ro"
GOOGLE_CLIENT_ID: "1234-abc.apps.googleusercontent.com"
FORWARD_HEADERS_STRATEGY: "framework"
COOKIE_SECURE: "true"
```

**Deploy from source** (Cloud Build builds the `Dockerfile`):

```bash
gcloud run deploy qr-attendance \
  --source . \
  --region "$REGION" \
  --allow-unauthenticated \
  --service-account "$SA" \
  --max-instances 1 \
  --cpu 1 --memory 1Gi --cpu-boost \
  --env-vars-file cloudrun.env.yaml \
  --set-secrets "QR_HMAC_SECRET=qr-hmac-secret:latest,HASH_PEPPER=hash-pepper:latest,GOOGLE_CLIENT_SECRET=google-client-secret:latest,DATABASE_PASSWORD=database-password:latest,REDIS_URL=redis-url:latest"
```

**Or deploy a prebuilt image:**

```bash
gcloud artifacts repositories create qr-attendance --repository-format=docker --location="$REGION"
IMAGE="$REGION-docker.pkg.dev/$PROJECT/qr-attendance/app:$(date +%Y%m%d-%H%M)"
gcloud builds submit --tag "$IMAGE" .
gcloud run deploy qr-attendance --image "$IMAGE" --region "$REGION" \
  --allow-unauthenticated --service-account "$SA" --max-instances 1 --cpu 1 --memory 1Gi --cpu-boost \
  --env-vars-file cloudrun.env.yaml \
  --set-secrets "QR_HMAC_SECRET=qr-hmac-secret:latest,HASH_PEPPER=hash-pepper:latest,GOOGLE_CLIENT_SECRET=google-client-secret:latest,DATABASE_PASSWORD=database-password:latest,REDIS_URL=redis-url:latest"
```

If you use the `run.app` URL rather than a custom domain, read it after the first deploy and set it:

```bash
URL="$(gcloud run services describe qr-attendance --region "$REGION" --format='value(status.url)')"
gcloud run services update qr-attendance --region "$REGION" --update-env-vars "PUBLIC_BASE_URL=$URL"
```

Then add `$URL/login/oauth2/code/google` to the Google OAuth client. Later deploys only need `--image` (or `--source`) and `--region`: the env vars, secrets and service account stay on the service. Old images in Artifact Registry count against its free storage — delete them or add a cleanup policy.

### Continuous deployment (GitHub Actions → Cloud Run)

[`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) deploys every merge to `master` that touches the
app (changes only to `*.md`, `docs/`, `scripts/`, `.github/` other than `deploy.yml`, `.env.example` or
`docker-compose.yml` are skipped). It can also be started by hand: *Actions > deploy > Run workflow* on `master`, or
`gh workflow run deploy.yml --ref master`.

1. Authenticate with **Workload Identity Federation**: GitHub's OIDC token is exchanged for a short-lived token of the
   deployer service account. No JSON key exists anywhere. The job runs in the GitHub environment `production`, and
   the provider accepts only tokens for this repository on `refs/heads/master`, from `deploy.yml`, in that
   environment. So pull requests, other branches, forks and other workflows on `master` (even ones with
   `id-token: write`) can't deploy.
2. Refuse to deploy a commit older than the one last deployed (protects against *Re-run jobs* on an old run).
3. `docker build` the `Dockerfile` on the runner, push `…/qr-attendance/app:<short-sha>` to Artifact Registry.
4. `gcloud run deploy --image … --no-traffic --tag candidate`: only the image changes (env vars, secrets, service
   account and scaling stay on the service). The new revision starts, **Flyway migrates the database**, and gcloud
   waits until the revision is Ready, while production still runs on the current revision.
5. Smoke test `https://candidate---<service-url>/actuator/health` (includes the database and Redis), with retries for a
   [cold start](#cold-start).
6. Only when it reports `UP`: `gcloud run services update-traffic --to-latest`, then check production's health.

If any step fails before 6, users never see the new revision. The job does nothing until the two repo variables
below are set.

**Merges by Dependabot auto-merge** are made with `GITHUB_TOKEN`, which doesn't trigger push workflows, so they are
not deployed on their own. They go out with the next deploy (the image is always built from the merged commit, which
contains them), or run the workflow by hand.

#### Migrations must be backward compatible

The new revision applies its Flyway migrations **before** traffic moves, and a traffic rollback doesn't undo them. For
a while the previous revision runs against the new schema, and with `ddl-auto: validate` it re-validates the schema on
every cold start. So every migration must work with the previous release too: add tables/columns (nullable or with a
default) freely; drop or rename in two steps (release 1 stops using the column, release 2 drops it). PostgreSQL runs
each migration in a transaction, so a failing migration leaves the schema unchanged and the revision never becomes
Ready.

#### One-time setup

Run once as a project owner. Nothing here is a secret: the provider name and service account email are stored as
repository **variables**.

```bash
PROJECT=qr-attendance-sanducc
PROJECT_NUMBER=1057355191791
REGION=europe-west1
GITHUB_REPO=SanduCC/qr-attendance
GITHUB_REPO_ID=1421377759   # gh api repos/SanduCC/qr-attendance --jq .id (survives renames; a re-created repo gets a new id)
DEPLOYER="qr-attendance-deployer@$PROJECT.iam.gserviceaccount.com"
RUNTIME="qr-attendance-run@$PROJECT.iam.gserviceaccount.com"

# 1. APIs for the token exchange (iamcredentials is usually on already)
gcloud services enable sts.googleapis.com iamcredentials.googleapis.com --project "$PROJECT"

# 2. A pool for GitHub Actions (reusable by other workflows, e.g. #26)
gcloud iam workload-identity-pools create github --project "$PROJECT" --location global \
  --display-name "GitHub Actions"

# 3. A provider that only accepts deploy.yml on this repository's master branch, in the production environment
#    (create the environment too, see "The production environment" below)
gcloud iam workload-identity-pools providers create-oidc qr-attendance --project "$PROJECT" --location global \
  --workload-identity-pool github \
  --display-name "SanduCC/qr-attendance master" \
  --issuer-uri "https://token.actions.githubusercontent.com" \
  --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_id=assertion.repository_id,attribute.ref=assertion.ref" \
  --attribute-condition "assertion.repository == '$GITHUB_REPO' && assertion.repository_id == '$GITHUB_REPO_ID' && assertion.ref == 'refs/heads/master' && assertion.workflow_ref == '$GITHUB_REPO/.github/workflows/deploy.yml@refs/heads/master' && assertion.environment == 'production'"

# 4. The deployer service account (no project-level roles)
gcloud iam service-accounts create qr-attendance-deployer --project "$PROJECT" \
  --display-name "qr-attendance deployer (GitHub Actions)"

# 5. Tokens from this repository may impersonate it
gcloud iam service-accounts add-iam-policy-binding "$DEPLOYER" --project "$PROJECT" \
  --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository_id/$GITHUB_REPO_ID"

# 6. Push images to this one Artifact Registry repository
gcloud artifacts repositories add-iam-policy-binding qr-attendance --project "$PROJECT" --location "$REGION" \
  --member "serviceAccount:$DEPLOYER" --role roles/artifactregistry.writer

# 7. Deploy revisions and move traffic on this one Cloud Run service
#    (run.developer, not run.admin: it can't change who may invoke the service)
gcloud run services add-iam-policy-binding qr-attendance --project "$PROJECT" --region "$REGION" \
  --member "serviceAccount:$DEPLOYER" --role roles/run.developer

# 8. Revisions run as the runtime service account: allow "act as" on that account only, not project-wide
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME" --project "$PROJECT" \
  --member "serviceAccount:$DEPLOYER" --role roles/iam.serviceAccountUser

# 9. Repository variables read by deploy.yml
gh variable set GCP_WIF_PROVIDER --repo "$GITHUB_REPO" \
  --body "projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/qr-attendance"
gh variable set GCP_DEPLOY_SA --repo "$GITHUB_REPO" --body "$DEPLOYER"

# 10. First run (also proves the setup)
gh workflow run deploy.yml --repo "$GITHUB_REPO" --ref master
```

The deployer doesn't need access to the secrets: Cloud Run checks that the *runtime* service account can read them.

#### The production environment

The `deploy` job runs in the GitHub environment **`production`** (*Settings > Environments*). Its deployment branch
policy allows only `master`, so GitHub refuses to run the job from any other branch or tag, and every run shows up
as a deployment (with the service URL) on the repository page. The OIDC token of a job in an environment carries
`environment: production` and `sub: repo:SanduCC/qr-attendance:environment:production`; `ref` stays
`refs/heads/master`, and the service account binding (step 5) matches on `repository_id`, so it is unaffected.

Optional: add a **required reviewer** to the environment for an approve-each-deploy gate. Every run then waits on
*Review deployments* before it gets any credentials, so merges to `master` no longer go out on their own.

To re-create it (e.g. for a re-created repository):

```bash
gh api -X PUT "repos/$GITHUB_REPO/environments/production" --input - <<'EOF'
{"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
EOF
gh api -X POST "repos/$GITHUB_REPO/environments/production/deployment-branch-policies" \
  -f name=master -f type=branch
```

#### Narrowing an existing provider

A provider created with the older condition (`... && assertion.ref == 'refs/heads/master'` only) accepts tokens from
any workflow on `master` of this repository that has `id-token: write`. To restrict it to `deploy.yml` in
`production`, in this order:

1. Merge the change that puts the `deploy` job in the `production` environment first: tokens of runs outside the
   environment have no `environment` claim and would be refused.
2. Update the condition (only the condition changes; the attribute mapping and the service account binding stay):

   ```bash
   gcloud iam workload-identity-pools providers update-oidc qr-attendance --project "$PROJECT" --location global \
     --workload-identity-pool github \
     --attribute-condition "assertion.repository == '$GITHUB_REPO' && assertion.repository_id == '$GITHUB_REPO_ID' && assertion.ref == 'refs/heads/master' && assertion.workflow_ref == '$GITHUB_REPO/.github/workflows/deploy.yml@refs/heads/master' && assertion.environment == 'production'"
   ```

3. Check that a deploy still authenticates: `gh workflow run deploy.yml --repo "$GITHUB_REPO" --ref master`, then
   `gh run watch --repo "$GITHUB_REPO"`. A manual (`workflow_dispatch`) run on `master` carries the same `ref`,
   `workflow_ref` and `environment` claims as a push run, so both pass. If the auth step is rejected by the
   attribute condition, a claim doesn't match: put the previous condition back with the same command while you look
   into it.

**Image retention.** A revision whose image was deleted can no longer start an instance, and with min-instances 0
every cold start pulls the image. Keep at least the newest 3 versions (current, previous for rollback, and one failed
candidate), about 0.5 GB with shared base layers (each image adds ~100 MB of its own: the app layer and ~31 MB of AOT cache):

```bash
cat > cleanup-policy.json <<'EOF'
[
  {"name": "delete-older", "action": {"type": "Delete"}, "condition": {"tagState": "any", "olderThan": "1d"}},
  {"name": "keep-latest-3", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 3}}
]
EOF
gcloud artifacts repositories set-cleanup-policies qr-attendance --project "$PROJECT" --location "$REGION" \
  --policy cleanup-policy.json
```

`set-cleanup-policies` replaces the repository's whole policy set (there is no `--overwrite` flag in current gcloud).

#### Rollback

A failed deploy needs no action: traffic was never moved (it stays pinned to the previous revision until the next
successful run). To go back after a bad release reached users:

```bash
gcloud run revisions list --service qr-attendance --region europe-west1 \
  --format "table(name,active,spec.containers[0].image,metadata.creationTimestamp)"
gcloud run services update-traffic qr-attendance --region europe-west1 --to-revisions <previous-revision>=100
```

This takes effect in seconds and doesn't touch the database (see above). Traffic is now pinned to that revision, so
a manual `gcloud run deploy` won't get traffic until you run
`gcloud run services update-traffic qr-attendance --region europe-west1 --to-latest`; the next workflow run does that
itself after its smoke test.
To deploy an older commit instead, revert it on `master` through a PR (the workflow refuses to deploy older commits).

**Deploys keep users signed in.** HTTP sessions (logins, a scanned QR token) live in Redis since #36, so a deploy
doesn't sign anyone out, and the teacher's projector page keeps running. Only an incompatible change to Spring
Security's serialized classes makes everyone sign in once; unreadable sessions are dropped, never a 500. The Boot 4
upgrade (#1, Spring Security 6.5 → 7.1) isn't one: Security 7 keeps the serialization version (`620L`), and a session
written by either version reads back in the other (security context with the OIDC user and roles, CSRF token, OAuth2
authorization request, saved request), so neither the deploy nor a traffic rollback signs anyone out.

### Scheduled jobs (Cloud Scheduler)

The app has two background jobs. Both are idempotent, so a retry or a second run changes nothing.

| Job | What it does | Why it matters |
|---|---|---|
| Retention purge | Deletes sessions opened before the start of the most recent academic year whose grace period is over (default: year ends 30 Sep, plus 30 days), their attendance records (cascade) and students left without records. Teachers and courses stay. | **GDPR**: must really run |
| Auto-close | Marks expired `OPEN` sessions `CLOSED` | cosmetic: an expired session already counts as closed everywhere |

Every purge logs one line with counts only, also when nothing was due, e.g.
`Retention purge: cutoff 2026-09-30T21:00:00Z, 12 session(s) and 87 student(s) deleted`.

**Where the app runs all the time** (docker compose, a VPS): in-app timers, on by default. The purge runs daily at 03:30
Europe/Bucharest (`attendance.retention.cron`), auto-close every minute. Nothing to set up.

**On Cloud Run**, Cloud Scheduler calls `POST /internal/jobs/daily` every night at 03:30 Europe/Bucharest. The endpoint
runs the purge and then auto-close, and answers with the counts as JSON. `JOBS_IN_APP_SCHEDULE=false` turns the
in-app timers off, so the jobs run once a night, from one place.

| Variable | Default | Notes |
|---|---|---|
| `JOBS_IN_APP_SCHEDULE` | `true` | `false` on Cloud Run |
| `JOBS_TRIGGER_SERVICE_ACCOUNT` | *(empty = endpoint disabled, every call gets 401)* | the only service account whose token is accepted |
| `JOBS_TRIGGER_AUDIENCE` | `PUBLIC_BASE_URL` + `/internal/jobs/daily` | the exact `aud` of the token; the setup pins it so a custom domain (#31) doesn't change it |

#### Why an endpoint, and how it's secured

The options were:
- **(a) Cloud Scheduler → HTTPS endpoint with a Google OIDC token** (chosen). It's one image and one service, so
  there's nothing extra to keep in sync, `deploy.yml` is unchanged, and the deployer service account needs no new
  permissions. It costs one request a night.
- **(b) A Cloud Run job running the same image in "purge and exit" mode.** It would need its own copy of the env vars
  and the five secret mounts, plus a `deploy.yml` step and `run.developer` on the job to keep its image current,
  or the purge would run old code against a migrated schema. It also boots Spring Boot (~20 s) for a job that takes
  milliseconds. It avoids a public endpoint, but that's the only advantage.
- **(c) Also purge on startup.** It's free, but it's no guarantee: when nobody opens the app for weeks (holidays),
  nothing starts.

The service is public (`--allow-unauthenticated`), so Cloud Run doesn't check the token. The app does, in its own
Spring Security filter chain for this one path (`JobTriggerSecurityConfig`, `JobTriggerTokens`):
- The token has to be a JWT signed with **RS256** by **Google's keys** (JWKS
  `https://www.googleapis.com/oauth2/v3/certs`, fetched on first use and cached). Unsigned (`alg: none`),
  HMAC-signed, forged and tampered tokens are rejected. The verification is Spring Security's `NimbusJwtDecoder`,
  not custom code.
- It needs `iss` = `https://accounts.google.com`, **`aud` exactly** `JOBS_TRIGGER_AUDIENCE` (a single value),
  **`email` exactly** `JOBS_TRIGGER_SERVICE_ACCOUNT` and `email_verified` = `true`. `exp` must be present and not
  past (60 s skew). A Google token minted for any other audience or account is useless.
- Only `POST` is allowed. CSRF is skipped for this path only, because no cookie or session is ever read or created
  there. Every other form POST still needs its CSRF token.
- A failure is a bare `401` (no token or a bad one) or `403` (wrong method), with no body and no
  `WWW-Authenticate` detail. The reason goes to the app log only, e.g. `Rejected a call to /internal/jobs/daily: … The aud
  claim is not valid`.
- There's a limit of **10 calls per minute per (hashed) IP**, checked before the token is parsed (`429`). The scheduler
  retries at least 10 minutes apart.
- The scheduler service account has **no IAM roles at all**. It's only an identity Google signs tokens for.

#### One-time setup

Run [`docs/scripts/setup-scheduled-jobs.ps1`](scripts/setup-scheduled-jobs.ps1) once in Windows PowerShell as a project
owner, **after** the release with `/internal/jobs/daily` is deployed. It's safe to re-run. It does the following:

```powershell
$gcloud = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$Project = 'qr-attendance-sanducc'; $Region = 'europe-west1'
$SchedulerSa = "qr-attendance-scheduler@$Project.iam.gserviceaccount.com"
$Uri = 'https://qr-attendance-1057355191791.europe-west1.run.app/internal/jobs/daily'   # PUBLIC_BASE_URL + path

# 1. API
& $gcloud services enable cloudscheduler.googleapis.com --project $Project
# 2. Scheduler identity: no roles (the service is public, so no run.invoker either)
& $gcloud iam service-accounts create qr-attendance-scheduler --project $Project `
    --display-name 'qr-attendance Cloud Scheduler (OIDC caller of /internal/jobs/daily, no roles)'
# 3. The app accepts only this account and audience; in-app timers off (new revision, same image and secrets)
& $gcloud run services update qr-attendance --project $Project --region $Region `
    --update-env-vars "JOBS_TRIGGER_SERVICE_ACCOUNT=$SchedulerSa,JOBS_TRIGGER_AUDIENCE=$Uri,JOBS_IN_APP_SCHEDULE=false"
# 4. The nightly job
& $gcloud scheduler jobs create http qr-attendance-daily-jobs --project $Project --location $Region `
    --schedule '30 3 * * *' --time-zone 'Europe/Bucharest' --uri $Uri --http-method POST `
    --oidc-service-account-email $SchedulerSa --oidc-token-audience $Uri `
    --attempt-deadline 180s --max-retry-attempts 3 --min-backoff 10m --max-backoff 1h `
    --description 'GDPR retention purge and auto-close (issue 26)'
# 5. Check: run it now, then look for the purge line
& $gcloud scheduler jobs run qr-attendance-daily-jobs --project $Project --location $Region
& $gcloud logging read 'resource.type=cloud_run_revision AND resource.labels.service_name=qr-attendance AND textPayload:RetentionJob' `
    --project $Project --freshness 10m --limit 3 --format 'value(timestamp,textPayload)'
```

As project owner you already have the two permissions this needs: `iam.serviceAccounts.actAs` on the scheduler
account, to create the job, and enabling the API. The Cloud Scheduler service agent gets its role automatically when
the API is enabled. `deploy.yml` and the deployer service account don't change. Env vars stay on the service across
deploys, like the others.

**Checking it later.** `gcloud scheduler jobs describe qr-attendance-daily-jobs --location europe-west1` shows the
last attempt and its status. The purge line is in the Cloud Run logs every night. If the scheduler gets a `401`,
the app log's `Rejected a call to /internal/jobs/daily: …` line names the failing check. Usually `JOBS_TRIGGER_AUDIENCE`
and the job's `--oidc-token-audience` differ. Change both together.

**Cost: $0.** A billing account gets 3 Cloud Scheduler jobs free, then each costs $0.10 a month. Check that other
projects on the same billing account don't already use the 3. Cloud Run sees one request a night: a ~20 s cold
start, about 600 vCPU-seconds a month against 180,000 free. Neon wakes once a night, for about 5 minutes of compute.
Upstash sees one or two commands for the rate limit.

### Email sign-in (SMTP)

Students without a Google account can sign in with a one-time code sent to their address (README,
[Email sign-in](../README.md#email-sign-in-optional)). The feature is **off** until an SMTP provider is set up and
`EMAIL_SIGN_IN_ENABLED=true`. With the flag on, the app refuses to start unless `MAIL_HOST` and `MAIL_FROM` are set.
TLS is forced in code: port 465 = SMTPS, any other port = STARTTLS, required, with the server certificate's host name
checked. A send waits at most 10 s, after which the student sees "We couldn't send the code. Please try again." The
mail server is not part of `/actuator/health`.

| Variable | Example | Notes |
|---|---|---|
| `EMAIL_SIGN_IN_ENABLED` | `true` | default `false` |
| `MAIL_HOST` | `smtp-relay.brevo.com` | |
| `MAIL_PORT` | `587` | default `587` (STARTTLS); `465` for SMTPS |
| `MAIL_USERNAME` | the provider's SMTP login | not a secret |
| `MAIL_PASSWORD` | the provider's SMTP key | **secret**: Secret Manager, never an env var in plain text |
| `MAIL_FROM` | `QR Attendance <attendance.qr@example.ro>` | must be a sender the provider has verified |

Volume: at most one mail per code. The app caps codes at 250 a day app-wide, 3 per address per 15 min, 5 per phone
per 15 min and 60 per IP per hour (`attendance.email-sign-in.limits`), so a free tier of ~300 a day is enough.

#### Which provider (snapshot October 2026, check the current terms)

| Provider | Free tier | Where / GDPR | Without your own domain |
|---|---|---|---|
| **Brevo** (recommended) | 300 mails/day, SMTP relay included | French company; data hosted in the EU (OVH France/Germany, Google Cloud Belgium); DPA in the terms | a single verified sender address works, but see the caveat below |
| Mailjet (Sinch) | 6,000/month, max 200/day | EU-based, DPA available | single verified sender, same caveat |
| Resend | 3,000/month, max 100/day, 1 domain | US company; EU sending region only on paid plans | needs a verified domain: not usable before #31 |
| Amazon SES | 3,000/month for 12 months (or the AWS free credits); sandbox until AWS grants production access | EU regions available, AWS DPA | verified identity, sandbox only mails verified recipients: too much setup for this |
| Gmail SMTP + app password | ~500/day | Google consumer terms, no DPA for this use | sends as a personal Gmail account; Google can block it at any time. **Not recommended** |

**Recommendation: Brevo.** It is EU-based with EU hosting, its free tier (300/day) covers several classes a day, and
it has plain SMTP relay with a separate SMTP key, so `MAIL_PASSWORD` is never an account password.

**Caveat until there's a custom domain (#31, parked).** Mail providers authenticate the *domain* of the sender
(DKIM, DMARC). You can't authenticate a domain you don't own: `gmail.com`, `yahoo.com` or a university address you
don't control. A single verified sender address still works, but:
- Brevo may rewrite the visible sender of mail from an unauthenticated domain to one of its own domains, so students
  see an unfamiliar address.
- Some mailboxes (Microsoft's Outlook/Hotmail in particular) are strict with unauthenticated senders, so codes may
  land in spam or be refused. The code page tells students to check their spam folder.
- Best before #31: use an address on a domain the school controls and have its IT add Brevo's DNS records. Next
  best: a dedicated mailbox created for the app, never a personal one. Once #31 gives the app its own domain,
  authenticate that domain in Brevo and switch `MAIL_FROM` to it.

#### One-time setup with Brevo (owner)

1. Create a free account at brevo.com with the app owner's work address. Brevo may ask for a company profile and may
   review a new account before it can send.
2. **Senders, Domains & Dedicated IPs → Senders → Add a sender**: name `QR Attendance`, the address for `MAIL_FROM`.
   Click the confirmation link Brevo emails to that address. If you control the address's domain, also authenticate
   the domain (**Domains → Add a domain**: Brevo code TXT, DKIM and DMARC records) for good deliverability.
3. **SMTP & API → SMTP**: note the SMTP server (`smtp-relay.brevo.com`), port `587` and the SMTP **login** (an
   address Brevo shows there). Generate a new **SMTP key** named `qr-attendance`. It is shown once; it's
   `MAIL_PASSWORD`.
4. Accept the DPA (it's part of Brevo's terms; a signed copy can be downloaded from the account) and add Brevo to
   the school's list of processors.
5. Put the key in Secret Manager and turn the feature on (Windows PowerShell; nothing here prints the key):

```powershell
$gcloud  = "$env:LOCALAPPDATA\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$Project = 'qr-attendance-sanducc'; $Region = 'europe-west1'
$RuntimeSa = "qr-attendance-run@$Project.iam.gserviceaccount.com"

# 1. The SMTP key as a secret, stored in the EU region only. Typed at a hidden prompt, written to a temp file
#    without a trailing newline (a PowerShell pipe would add one), then the file is deleted.
$key = Read-Host 'Brevo SMTP key' -AsSecureString
$tmp = New-TemporaryFile
[IO.File]::WriteAllText($tmp, [Net.NetworkCredential]::new('', $key).Password)
& $gcloud secrets create mail-password --project $Project `
    --replication-policy=user-managed --locations=$Region --data-file=$tmp
Remove-Item $tmp
# 2. Only the runtime service account may read it
& $gcloud secrets add-iam-policy-binding mail-password --project $Project `
    --member="serviceAccount:$RuntimeSa" --role=roles/secretmanager.secretAccessor
# 3. Mail settings plus the secret; still off (new revision, same image)
& $gcloud run services update qr-attendance --project $Project --region $Region `
    --update-secrets 'MAIL_PASSWORD=mail-password:latest' `
    --update-env-vars 'MAIL_HOST=smtp-relay.brevo.com,MAIL_PORT=587,MAIL_USERNAME=XXXXXX@smtp-brevo.com,MAIL_FROM=QR Attendance <attendance.qr@example.ro>'
# 4. Turn it on (outside class time). The revision fails to start, and traffic stays on the old one, if MAIL_HOST
#    or MAIL_FROM is missing.
& $gcloud run services update qr-attendance --project $Project --region $Region `
    --update-env-vars 'EMAIL_SIGN_IN_ENABLED=true'
```

Then test it once yourself: open `/login/email` on a phone, request a code to a personal inbox, check that it arrives
(and not in spam), and sign in. The Cloud Run log shows `Sign-in code sent to a***@…` with a masked address; codes and
full addresses are never logged. To turn it off again, set `EMAIL_SIGN_IN_ENABLED=false` in the same way; email
students keep their attendance.

**Rotating the key:** generate a new SMTP key in Brevo, then add it with `& $gcloud secrets versions add mail-password
--project $Project --data-file=$tmp` (same temp-file steps). Deploy a new revision, or run any `services update`, so
`latest` is read again, then delete the old key in Brevo.

**Rolling back** once email students exist: the release before this feature can't read `provider = 'EMAIL'` rows,
so teacher pages that list those students would fail. Roll back only to a release that has the feature (it can stay
switched off).

---

## (b) Oracle Cloud Always Free ARM VM + docker compose + Caddy

An Ampere (ARM64) VM from Oracle's Always Free tier in an EU home region (e.g. Frankfurt, Amsterdam, Paris, Milan, Stockholm, Madrid, Marseille, Zurich). Run the whole stack with docker compose and put Caddy in front for automatic HTTPS — same files as option (c).

**Pros:** the most free capacity of any option (several ARM cores and many GB of RAM in total), always on, no cold starts.

**Caveats:**

- **Capacity.** Free ARM instances are often unavailable in popular regions ("out of host capacity"); you may need to retry over days. The home region is fixed at sign-up.
- **Idle reclamation.** Oracle may reclaim Always Free instances it considers idle (low CPU, network and memory use over a period). An attendance app is idle most of the week, so read the current policy; upgrading the account to Pay-As-You-Go (still free within Always Free limits) is commonly reported to avoid reclamation.
- **You run the server:** OS updates, firewall (both the VCN security list and the VM's own firewall need ports 80/443), Docker updates, monitoring and **backups** are your job.
- **ARM64:** build the image on the VM (`docker compose build`) or build multi-arch images; the `amazoncorretto`, `postgres`, `redis` and `caddy` base images all publish arm64 variants.

---

## (c) Cheap EU VPS (e.g. Hetzner) + docker compose + Caddy

A small VPS in Germany/Finland (Hetzner) or another EU provider for roughly **€4–5/month** (check current pricing; x86 and ARM plans both work). Most predictable: fixed price, always on, EU-only data, no free-tier surprises.

On the server (Docker + Compose plugin installed, DNS `A`/`AAAA` record for your domain pointing at it):

```bash
git clone https://github.com/SanduCC/qr-attendance.git && cd qr-attendance
cp .env.example .env && chmod 600 .env    # fill in secrets, PUBLIC_BASE_URL=https://attendance.example.ro
```

`Caddyfile` (Caddy obtains and renews the Let's Encrypt certificate automatically):

```caddyfile
attendance.example.ro {
    reverse_proxy app:8080
}
```

`docker-compose.prod.yml` — adds Caddy and stops publishing Postgres/Redis/app ports on the host:

```yaml
services:
  postgres:
    ports: !reset []
  redis:
    ports: !reset []
  app:
    ports: !reset []
    environment:
      FORWARD_HEADERS_STRATEGY: framework
      COOKIE_SECURE: "true"
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - app

volumes:
  caddy_data:
  caddy_config:
```

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Notes:

- `!reset` needs a recent Docker Compose v2. Removing the published ports matters: **Docker-published ports bypass `ufw`**, so a `5432:5432` mapping would expose Postgres to the internet.
- The base `docker-compose.yml` uses the dev password `attendance`. For production, change `POSTGRES_PASSWORD` and `DATABASE_PASSWORD` in the override **before the first start** (Postgres only applies it when the volume is first initialised).
- Backups, e.g. a nightly cron job, copied off the server:

  ```bash
  docker compose exec -T postgres pg_dump -U attendance -Fc attendance > "backup-$(date +%F).dump"
  ```

- Updates: `git pull && docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`. Enable unattended OS security updates.

---

## (d) University / school IT hosting

Ask your IT department for a VM or container slot inside the institution. Usually the simplest route for GDPR: data stays on infrastructure the university already controls and has documented, and the data protection officer (DPO) is in the loop from the start. They may also provide a domain, TLS certificate and Google Workspace integration (which pairs well with the planned Workspace domain lock). The trade-off is lead time and whatever their platform requires (often a container image plus a managed Postgres; Redis may need to be requested).

---

## (e) Render / Railway / Fly.io / Koyeb

Convenient git-push deploys, but at the time of writing their free offers either **sleep when idle** (wake-up can take tens of seconds to a minute for a JVM app — too slow when 30 students scan at once) or are **trial credits** that run out, and some free databases expire. Fine for a demo; for a real class use a paid plan on these or one of the options above.

---

## GDPR checklist for production

- [ ] Database, Redis and app hosted in **EU regions**; data processing agreements (DPAs) accepted with every provider. Redis holds the HTTP sessions, including a signed-in user's Google profile (name, email), until 60 minutes of inactivity.
- [ ] **HTTPS only** (`COOKIE_SECURE=true`, valid certificate, `FORWARD_HEADERS_STRATEGY=framework` behind the proxy,
  `TRUSTED_PROXY_HOPS` matching the proxies in front: [Client IP behind a proxy](#client-ip-behind-a-proxy)).
- [ ] Secrets in a **secret manager** (or a root-only `chmod 600` `.env` on a VPS) — never in git, images or CI logs.
- [ ] Postgres and Redis **not exposed** to the internet; strong, non-default passwords.
- [ ] **Backups** automated, encrypted, stored in the EU, and a restore tested at least once.
- [ ] **Retention purge job** runs daily: the in-app timer on always-on hosts, or Cloud Scheduler on scale-to-zero hosts ([Scheduled jobs](#scheduled-jobs-cloud-scheduler)). Check for the nightly `Retention purge: cutoff …` log line.
- [ ] **Privacy notice** shown on the student page before sign-in, naming the controller (school/teacher), purpose, data stored and retention.
- [ ] Only minimal data stored: name, email, provider subject, check-in time; IP and device ID **hashed with the pepper**, never raw.
- [ ] If [email sign-in](#email-sign-in-smtp) is on: the mail provider is an EU-hosted processor with an accepted DPA
  and is named in the privacy notice. Sign-in codes exist only as peppered hashes in Redis, for at most 10 minutes.
- [ ] Access logs on the proxy/host don't keep raw IPs longer than needed.
- [ ] Teacher list at `/admin/teachers` reviewed each term (deactivate people who left); `ADMIN_EMAILS` kept to the people who really manage teachers.
