#!/usr/bin/env bash
# Cold-start benchmark for the app image (issue #28). Used by .github/workflows/startup-benchmark.yml; runs locally
# too, with Postgres and Redis on localhost (e.g. `docker compose up -d postgres redis`).
#
#   scripts/startup-benchmark.sh LABEL=IMAGE[@JAVA_TOOL_OPTIONS][@ENV=VALUE,ENV=VALUE] ...
#   scripts/startup-benchmark.sh base=qra:base pr=qra:pr 'pr-c1=qra:pr@-XX:TieredStopAtLevel=1'
#   scripts/startup-benchmark.sh --verify-aot qra:pr
#
# Each run starts a fresh container (cold JVM) limited like the Cloud Run service (CPUS, default 1; 512 MiB) and
# measures:
#   - started: Spring's "Started AttendanceApplication in X seconds"
#   - jvm:     its "process running for Y" (includes JVM start and the launcher)
#   - ready:   `docker run` until /actuator/health answers UP (database + Redis included)
#   - page:    the first GET / after that (first Thymeleaf render, first Redis session access)
#   - memory:  container memory right after the first page
# Runs are interleaved (A B C, A B C, ...) so drift on the runner affects every variant alike. Prints a Markdown
# table with the median of each column.
set -euo pipefail

RUNS="${RUNS:-3}"
CPUS="${CPUS:-1}"
MEMORY="${MEMORY:-512m}"
PORT="${PORT:-8080}"
DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://localhost:5432/attendance}"
DATABASE_USERNAME="${DATABASE_USERNAME:-attendance}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-attendance}"
REDIS_URL="${REDIS_URL:-redis://localhost:6379}"
CONTAINER=qra-startup-benchmark
WORK="$(mktemp -d)"
trap 'docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

now_ms() { date +%s%3N; }

# Throwaway values; the benchmark never signs anyone in.
random_secret() { head -c 48 /dev/urandom | base64 | tr -d '\n'; }
QR_HMAC_SECRET="$(random_secret)"
HASH_PEPPER="$(random_secret)"
export QR_HMAC_SECRET HASH_PEPPER

# start_container IMAGE JAVA_TOOL_OPTIONS EXTRA_ENV(comma-separated KEY=VALUE)
start_container() {
  local image="$1" tool_options="$2" extra_env="$3" args=()
  args+=(--name "$CONTAINER" --detach --network host --cpus "$CPUS" --memory "$MEMORY")
  args+=(-e PORT="$PORT" -e DATABASE_URL="$DATABASE_URL" -e DATABASE_USERNAME="$DATABASE_USERNAME")
  args+=(-e DATABASE_PASSWORD="$DATABASE_PASSWORD" -e REDIS_URL="$REDIS_URL")
  args+=(-e GOOGLE_CLIENT_ID=benchmark -e GOOGLE_CLIENT_SECRET=benchmark -e QR_HMAC_SECRET -e HASH_PEPPER)
  args+=(-e COOKIE_SECURE=false)
  [[ -n "$tool_options" ]] && args+=(-e JAVA_TOOL_OPTIONS="$tool_options")
  if [[ -n "$extra_env" ]]; then
    local pair
    IFS=',' read -r -a pairs <<<"$extra_env"
    for pair in "${pairs[@]}"; do args+=(-e "$pair"); done
  fi
  docker run "${args[@]}" "$image" >/dev/null
}

wait_until_up() {
  local deadline=$(( $(now_ms) + 180000 ))
  until curl -fsS --max-time 2 "http://localhost:$PORT/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
    if (( $(now_ms) > deadline )) || [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER")" != true ]]; then
      echo "Container did not become healthy:" >&2
      docker logs "$CONTAINER" 2>&1 | tail -50 >&2
      return 1
    fi
    sleep 0.1
  done
}

# Prints: started jvm ready page memory_mib
measure_once() {
  local image="$1" tool_options="$2" extra_env="$3" t0 t_ready page logs started jvm mem
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  t0="$(now_ms)"
  start_container "$image" "$tool_options" "$extra_env"
  wait_until_up
  t_ready="$(now_ms)"
  page="$(curl -fsS -o /dev/null -w '%{time_total}' "http://localhost:$PORT/")"
  mem="$(docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER" | awk '{print $1}')"
  logs="$(docker logs "$CONTAINER" 2>&1)"
  docker rm -f "$CONTAINER" >/dev/null
  if grep -Eq '\[(error|warning) *\]\[aot' <<<"$logs"; then
    echo "AOT cache problem in $image:" >&2
    grep -E '\[(error|warning) *\]\[aot' <<<"$logs" | head -5 >&2
  fi
  started="$(sed -nE 's/.*Started AttendanceApplication in ([0-9.]+) seconds \(process running for ([0-9.]+)\).*/\1/p' <<<"$logs")"
  jvm="$(sed -nE 's/.*Started AttendanceApplication in ([0-9.]+) seconds \(process running for ([0-9.]+)\).*/\2/p' <<<"$logs")"
  echo "${started:-nan} ${jvm:-nan} $(awk -v a="$t0" -v b="$t_ready" 'BEGIN{printf "%.2f", (b-a)/1000}') $page $mem"
}

median() { sort -g | awk '{v[NR]=$1} END{ if (NR%2) print v[(NR+1)/2]; else printf "%.3f\n", (v[NR/2]+v[NR/2+1])/2 }'; }

if [[ "${1:-}" == "--verify-aot" ]]; then
  # The cache must load (no "[error][aot]") and the classes must come AOT-linked from it, at the CPUS/MEMORY limits.
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  start_container "$2" "-Xlog:aot" ""
  wait_until_up
  logs="$(docker logs "$CONTAINER" 2>&1)"
  docker rm -f "$CONTAINER" >/dev/null
  grep -E '\[aot\] (Opened AOT cache|Using AOT-linked classes|full module graph|Loaded heap)|\[warning\]\[aot' <<<"$logs" || true
  if grep -Eq '\[error *\]\[aot' <<<"$logs" || ! grep -q 'Using AOT-linked classes: true' <<<"$logs"; then
    echo "::error::The AOT cache in $2 was not used:" >&2
    grep -E '\[error *\]\[aot' <<<"$logs" | head -20 >&2
    exit 1
  fi
  echo "AOT cache in $2 is used."
  exit 0
fi

labels=()
for spec in "$@"; do
  label="${spec%%=*}"
  rest="${spec#*=}"
  IFS='@' read -r image tool_options extra_env <<<"$rest"
  labels+=("$label")
  printf '%s\n%s\n%s\n' "$image" "${tool_options:-}" "${extra_env:-}" >"$WORK/$label.spec"
  # Untimed first start per variant: applies the Flyway migrations to an empty database and warms the page cache.
  echo "warm-up: $label" >&2
  measure_once "$image" "${tool_options:-}" "${extra_env:-}" >/dev/null
done

for run in $(seq 1 "$RUNS"); do
  for label in "${labels[@]}"; do
    { read -r image; read -r tool_options || true; read -r extra_env || true; } <"$WORK/$label.spec"
    result="$(measure_once "$image" "$tool_options" "$extra_env")"
    echo "run $run $label: $result" >&2
    echo "$result" >>"$WORK/$label.results"
  done
done

echo "| variant ($CPUS vCPU, $MEMORY) | started (s) | process running (s) | docker run → health UP (s) | first GET / (s) | memory |"
echo "|---|---|---|---|---|---|"
for label in "${labels[@]}"; do
  f="$WORK/$label.results"
  printf '| %s | %s | %s | %s | %s | %s |\n' "$label" \
    "$(awk '{print $1}' "$f" | median)" "$(awk '{print $2}' "$f" | median)" \
    "$(awk '{print $3}' "$f" | median)" "$(awk '{print $4}' "$f" | median)" "$(awk '{print $5}' "$f" | tail -1)"
done
echo
echo "Median of $RUNS cold starts per variant; memory from the last run."
