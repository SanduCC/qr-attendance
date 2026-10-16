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
- `COOKIE_SECURE=true` (the default).
- Secrets (`GOOGLE_CLIENT_SECRET`, `QR_HMAC_SECRET`, `HASH_PEPPER`, `DATABASE_PASSWORD`) from a secret manager or a root-only `.env`, never committed.
- In the Google OAuth client, add `{PUBLIC_BASE_URL}/login/oauth2/code/google` as an authorized redirect URI.

---

## (a) Google Cloud Run + Neon (or Supabase) Postgres + Upstash Redis

Serverless container plus managed free-tier databases, all in EU regions.

- **Cloud Run** region: `europe-central2` (Warsaw, closest to Romania) or `europe-west1` (Belgium). Pricing tiers differ per region — check which regions the free tier covers.
- **Postgres:** [Neon](https://neon.tech) (e.g. AWS Frankfurt) or [Supabase](https://supabase.com) (e.g. Frankfurt). Use the *direct* (non-pooled) connection string, with `sslmode=require`, e.g. `DATABASE_URL=jdbc:postgresql://ep-xxxx.eu-central-1.aws.neon.tech/attendance?sslmode=require`. Free databases suspend when idle; the first query after a pause is slower.
- **Redis:** [Upstash](https://upstash.com) in an EU region, TLS URL: `REDIS_URL=rediss://default:<password>@<name>.upstash.io:6379`. Redis is **required**: it holds the HTTP sessions (Spring Session, keys `qra:session:sessions:<id>`) as well as the rate-limit counters. Session keys use a TTL and no keyspace notifications, so no `CONFIG` command is needed (Upstash doesn't allow it). See [Upstash command budget](#upstash-command-budget) below.

### Caveats

- **Cold starts.** With zero instances running, the first request starts the JVM and Spring Boot — about 17 s with 512 MiB, 1 vCPU and CPU boost (#28). In practice the teacher opening the display page warms the instance before students scan. `--cpu-boost` helps; `--min-instances=1` removes cold starts but is billed while idle.
- **Instances.** HTTP sessions (the scanned QR token bound to the browser, the OAuth login state, the sign-in) live in Redis, so `--max-instances` can be raised above 1, and a deploy doesn't sign anyone out (the projector page keeps rotating its QR code). The in-app scheduled jobs (if enabled) then run on every instance. Both are idempotent: auto-close is a single `UPDATE … WHERE status = OPEN AND closes_at <= now`, and the retention purge is a pair of `DELETE … WHERE` statements with a fixed cutoff, so a second run changes nothing. The Upstash command budget is per database, not per instance.
- **Redis down.** Pages that need a session (scan, sign-in, check-in, teacher pages) answer **503 "temporarily unavailable, try again in a minute"** with `Retry-After`. They never continue without the session. The rate limiter fails open. `/actuator/health` reports DOWN (503) while Redis is unreachable. That's accurate, but don't use it as a liveness probe: restarting the app doesn't fix Redis.
- **Background work.** With request-based billing, CPU is throttled outside requests and the instance may be scaled to zero, so in-app timers only fire while an instance happens to be alive, and for the GDPR retention purge the next active day may be weeks away. On Cloud Run, Cloud Scheduler therefore triggers the jobs over HTTPS and the in-app timers are off: see [Scheduled jobs](#scheduled-jobs-cloud-scheduler).
- **Live updates.** The projector page polls every 3 s, so Cloud Run's request timeout (default 5 min, max 60 min) doesn't affect it. If the live table moves to SSE (#6), clients will have to reconnect after each timeout.
- `FORWARD_HEADERS_STRATEGY=framework` is required (Cloud Run terminates TLS).
- `PUBLIC_BASE_URL` = the `https://<service>-<hash>.<region>.run.app` URL or your custom domain.

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
   deployer service account. No JSON key exists anywhere. The provider accepts only tokens for this repository on
   `refs/heads/master`, so pull requests, other branches and forks can't deploy.
2. Refuse to deploy a commit older than the one last deployed (protects against *Re-run jobs* on an old run).
3. `docker build` the `Dockerfile` on the runner, push `…/qr-attendance/app:<short-sha>` to Artifact Registry.
4. `gcloud run deploy --image … --no-traffic --tag candidate`: only the image changes (env vars, secrets, service
   account and scaling stay on the service). The new revision starts, **Flyway migrates the database**, and gcloud
   waits until the revision is Ready, while production still runs on the current revision.
5. Smoke test `https://candidate---<service-url>/actuator/health` (includes the database and Redis), with retries for a
   ~17–19 s cold start.
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

# 3. A provider that only accepts this repository's master branch
gcloud iam workload-identity-pools providers create-oidc qr-attendance --project "$PROJECT" --location global \
  --workload-identity-pool github \
  --display-name "SanduCC/qr-attendance master" \
  --issuer-uri "https://token.actions.githubusercontent.com" \
  --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_id=assertion.repository_id,attribute.ref=assertion.ref" \
  --attribute-condition "assertion.repository == '$GITHUB_REPO' && assertion.repository_id == '$GITHUB_REPO_ID' && assertion.ref == 'refs/heads/master'"

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

**Image retention.** A revision whose image was deleted can no longer start an instance, and with min-instances 0
every cold start pulls the image. Keep at least the newest 3 versions (current, previous for rollback, and one failed
candidate), about 0.4 GB with shared base layers:

```bash
cat > cleanup-policy.json <<'EOF'
[
  {"name": "delete-older", "action": {"type": "Delete"}, "condition": {"tagState": "any", "olderThan": "1d"}},
  {"name": "keep-latest-3", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 3}}
]
EOF
gcloud artifacts repositories set-cleanup-policies qr-attendance --project "$PROJECT" --location "$REGION" \
  --policy cleanup-policy.json --overwrite
```

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

**Deploys end users' sessions.** HTTP sessions (logins, a scanned QR token) are in memory, so after the traffic
switch everyone signs in again, including the teacher's projector page. Avoid merging app changes during a class.

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
- [ ] **HTTPS only** (`COOKIE_SECURE=true`, valid certificate, `FORWARD_HEADERS_STRATEGY=framework` behind the proxy).
- [ ] Secrets in a **secret manager** (or a root-only `chmod 600` `.env` on a VPS) — never in git, images or CI logs.
- [ ] Postgres and Redis **not exposed** to the internet; strong, non-default passwords.
- [ ] **Backups** automated, encrypted, stored in the EU, and a restore tested at least once.
- [ ] **Retention purge job** runs daily: the in-app timer on always-on hosts, or Cloud Scheduler on scale-to-zero hosts ([Scheduled jobs](#scheduled-jobs-cloud-scheduler)). Check for the nightly `Retention purge: cutoff …` log line.
- [ ] **Privacy notice** shown on the student page before sign-in, naming the controller (school/teacher), purpose, data stored and retention.
- [ ] Only minimal data stored: name, email, provider subject, check-in time; IP and device ID **hashed with the pepper**, never raw.
- [ ] Access logs on the proxy/host don't keep raw IPs longer than needed.
- [ ] Teacher list at `/admin/teachers` reviewed each term (deactivate people who left); `ADMIN_EMAILS` kept to the people who really manage teachers.
