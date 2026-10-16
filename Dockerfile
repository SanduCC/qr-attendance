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

# AOT cache (JEP 483/514/515, issue #28): a training run starts the Spring context once (until the context is
# refreshed, then exits) and records the loaded + linked classes and method profiles in /app/app.aot. Production
# starts read them from there instead of parsing, verifying and linking thousands of classes again (~40-50% less
# startup CPU). The training run needs no database or Redis: Flyway and schema validation are off, Hibernate gets
# an explicit dialect instead of asking the database, and nothing connects to Redis before the context is
# refreshed. The secrets are random throwaway values that exist only inside this RUN (they are not in the image).
#
# The cache is only valid for this exact JDK and these exact jar files (path, size, mtime), which is why it is
# built here, in the final stage, and why the JVM flags below match the ENTRYPOINT's. If it ever doesn't match,
# the JVM logs "[error][aot] ..." and starts normally without it.
RUN GOOGLE_CLIENT_ID=aot-training GOOGLE_CLIENT_SECRET=aot-training \
    QR_HMAC_SECRET="$(head -c 48 /dev/urandom | base64)" HASH_PEPPER="$(head -c 48 /dev/urandom | base64)" \
    java -XX:AOTCacheOutput=/app/app.aot -Xlog:aot=error \
      -XX:MaxRAMPercentage=75 -XX:+UseSerialGC --sun-misc-unsafe-memory-access=allow \
      -Dspring.context.exit=onRefresh \
      -Dattendance.startup.warm-up-database=false \
      -Dspring.flyway.enabled=false \
      -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.database=postgresql \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -jar /app/app.jar \
    && rm -rf /tmp/hsperfdata_root \
    && ls -l /app/app.aot

USER app
ENV PORT=8080
EXPOSE 8080

# busybox wget is available in the Alpine base image.
# Ignored by Cloud Run (it uses its own startup/liveness probes).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null "http://localhost:${PORT:-8080}/actuator/health" || exit 1

# Serial GC + percentage-based heap: target hosts are tiny (<= 1 vCPU, 512 MB - 1 GB).
# --sun-misc-unsafe-memory-access=allow: Netty (Redis client) still uses sun.misc.Unsafe; this keeps JDK 23's
# behaviour without the "terminally deprecated method" warning at every start.
ENTRYPOINT ["java","-XX:AOTCache=/app/app.aot","-XX:MaxRAMPercentage=75","-XX:+UseSerialGC","--sun-misc-unsafe-memory-access=allow","-jar","/app/app.jar"]
