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
- **Redis:** [Upstash](https://upstash.com) in an EU region, TLS URL: `REDIS_URL=rediss://default:<password>@<name>.upstash.io:6379`. The free plan has a monthly command quota; one class uses very little of it.

### Caveats

- **Cold starts.** With zero instances running, the first request starts the JVM and Spring Boot — about 17 s with 512 MiB, 1 vCPU and CPU boost (#28). In practice the teacher opening the display page warms the instance before students scan. `--cpu-boost` helps; `--min-instances=1` removes cold starts but is billed while idle.
- **Keep `--max-instances=1`.** HTTP sessions (the scanned QR token bound to the browser, the OAuth login state) live in memory. With several instances a student could land on an instance that doesn't know their session. Scaling out requires adding Spring Session with Redis first.
- **Background work.** With request-based billing, CPU is throttled outside requests and the instance may be scaled to zero, so scheduled jobs (e.g. the daily retention purge) only run while an instance is alive. For the GDPR retention purge, "next active day" may be weeks away, so trigger it with Cloud Scheduler (#26).
- **Live updates.** The projector page polls every 3 s, so Cloud Run's request timeout (default 5 min, max 60 min) doesn't affect it. If the live table moves to SSE (#6), clients will have to reconnect after each timeout.
- `FORWARD_HEADERS_STRATEGY=framework` is required (Cloud Run terminates TLS).
- `PUBLIC_BASE_URL` = the `https://<service>-<hash>.<region>.run.app` URL or your custom domain.

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

- [ ] Database, Redis and app hosted in **EU regions**; data processing agreements (DPAs) accepted with every provider.
- [ ] **HTTPS only** (`COOKIE_SECURE=true`, valid certificate, `FORWARD_HEADERS_STRATEGY=framework` behind the proxy).
- [ ] Secrets in a **secret manager** (or a root-only `chmod 600` `.env` on a VPS) — never in git, images or CI logs.
- [ ] Postgres and Redis **not exposed** to the internet; strong, non-default passwords.
- [ ] **Backups** automated, encrypted, stored in the EU, and a restore tested at least once.
- [ ] **Retention purge job** runs daily (on scale-to-zero hosts, verify it actually runs or trigger it externally).
- [ ] **Privacy notice** shown on the student page before sign-in, naming the controller (school/teacher), purpose, data stored and retention.
- [ ] Only minimal data stored: name, email, provider subject, check-in time; IP and device ID **hashed with the pepper**, never raw.
- [ ] Access logs on the proxy/host don't keep raw IPs longer than needed.
- [ ] Teacher list at `/admin/teachers` reviewed each term (deactivate people who left); `ADMIN_EMAILS` kept to the people who really manage teachers.
