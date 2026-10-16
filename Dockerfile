# syntax=docker/dockerfile:1

# ---------- Build stage ----------
# The Maven wrapper (only-script) needs: sh, wget or curl, and unzip or tar.
# Alpine's busybox already provides sh, wget (with HTTPS via ssl_client), unzip and tar,
# so no extra packages are installed.
FROM amazoncorretto:25-alpine AS build
WORKDIR /workspace

# 1) Wrapper + POM first, so the dependency layer is cached until pom.xml changes.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
# Strip CR in case the file was checked out with CRLF line endings on Windows.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && ./mvnw -B -q dependency:go-offline

# 2) Sources, then package (tests run in CI, not in the image build).
#    The fat jar is then extracted into app.jar + lib/ (plain jars on the class path): the JVM's AOT cache below
#    can only cache classes loaded from plain jars, not from jars nested inside the Spring Boot jar.
COPY src/ src/
RUN ./mvnw -B -DskipTests package \
    && java -Djarmode=tools -jar target/qr-attendance-*.jar extract \
         --destination /workspace/extracted --application-filename app.jar

# ---------- Runtime stage ----------
FROM amazoncorretto:25-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app -h /app app

# Owned by root, so the app user can read but not modify the code or the AOT cache.
COPY --from=build /workspace/extracted/ /app/

# JVM flags of production AND of the AOT training run below (the cache is only usable with matching flags).
# - Serial GC + percentage-based heap: target hosts are tiny (<= 1 vCPU, 512 MB - 1 GB).
# - InitialRAMPercentage=25 (128 MB of 512 MiB): the AOT cache's archived heap objects (~6 MB) are loaded into the
#   old generation at JVM start. With the default initial heap (1/64 of RAM = 8 MB) they don't fit, and the JVM
#   then refuses to start ("archived heap objects cannot be loaded ... unrecoverable error").
# - TieredStopAtLevel=1: JIT-compile with C1 only. The C2 compiler's work during startup competes with the app for
#   the single vCPU (startup -35% in the CI benchmark, and less memory); C1 code is slower at peak, which doesn't
#   matter at this app's few requests per second.
# - --sun-misc-unsafe-memory-access=allow: Netty (Redis client) still uses sun.misc.Unsafe; keeps JDK 23's
#   behaviour without the "terminally deprecated method" warning at every start.
RUN printf '%s\n' -XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=25 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 \
      --sun-misc-unsafe-memory-access=allow > /app/jvm.options

# AOT cache (JEP 483/514/515, issue #28). A training run starts the Spring context once (until it is refreshed,
# then exits) and records the loaded and linked classes in /app/app.aot. Production starts map them from there
# instead of loading, verifying and linking thousands of classes again (startup -50% in the CI benchmark).
# - No database or Redis at build time: Flyway, schema validation and the database warm-up are off, Hibernate gets
#   an explicit dialect instead of asking the database, and nothing connects to Redis before the refresh.
# - The secrets are random throwaway values that exist only inside this RUN; they are not in the image.
# - The cache is only valid for this exact JDK and these exact jar files (path, size, mtime), which is why it is
#   built here, in the final stage.
# - Then a check run with production's flags at Cloud Run's 512 MiB and -XX:AOTMode=on (any problem with the cache
#   is fatal): an image whose cache production couldn't use fails here instead of at deploy time.
RUN set -e; \
    export GOOGLE_CLIENT_ID=aot-training GOOGLE_CLIENT_SECRET=aot-training; \
    QR_HMAC_SECRET="$(head -c 48 /dev/urandom | base64)"; HASH_PEPPER="$(head -c 48 /dev/urandom | base64)"; \
    export QR_HMAC_SECRET HASH_PEPPER; \
    offline="-Dspring.context.exit=onRefresh -Dattendance.startup.warm-up-database=false \
      -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none -Dspring.jpa.database=postgresql \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false"; \
    java @/app/jvm.options -XX:AOTCacheOutput=/app/app.aot -Xlog:aot=error $offline -jar /app/app.jar; \
    java @/app/jvm.options -XX:AOTCache=/app/app.aot -XX:AOTMode=on -XX:MaxRAM=512m $offline -jar /app/app.jar \
      > /tmp/aot-check.log 2>&1 || { tail -50 /tmp/aot-check.log; exit 1; }; \
    if grep -E '\[error *\]\[aot' /tmp/aot-check.log; then exit 1; fi; \
    echo "AOT cache check passed: $(grep -o 'Root WebApplicationContext: initialization completed in [0-9]* ms' /tmp/aot-check.log)"; \
    rm -rf /tmp/aot-check.log /tmp/hsperfdata_root; \
    ls -l /app/app.aot

USER app
ENV PORT=8080
EXPOSE 8080

# busybox wget is available in the Alpine base image.
# Ignored by Cloud Run (it uses its own startup/liveness probes).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null "http://localhost:${PORT:-8080}/actuator/health" || exit 1

ENTRYPOINT ["java","@/app/jvm.options","-XX:AOTCache=/app/app.aot","-jar","/app/app.jar"]
