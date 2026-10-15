# syntax=docker/dockerfile:1

# ---------- Build stage ----------
# The Maven wrapper (only-script) needs: sh, wget or curl, and unzip or tar.
# Alpine's busybox already provides sh, wget (with HTTPS via ssl_client), unzip and tar,
# so no extra packages are installed.
FROM amazoncorretto:27-alpine AS build
WORKDIR /workspace

# 1) Wrapper + POM first, so the dependency layer is cached until pom.xml changes.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
# Strip CR in case the file was checked out with CRLF line endings on Windows.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && ./mvnw -B -q dependency:go-offline

# 2) Sources, then package (tests run in CI, not in the image build).
COPY src/ src/
RUN ./mvnw -B -DskipTests package \
    && cp target/qr-attendance-*.jar /workspace/app.jar

# ---------- Runtime stage ----------
FROM amazoncorretto:27-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app -h /app app

COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER app
ENV PORT=8080
EXPOSE 8080

# busybox wget is available in the Alpine base image.
# Ignored by Cloud Run (it uses its own startup/liveness probes).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null "http://localhost:${PORT:-8080}/actuator/health" || exit 1

# Serial GC + percentage-based heap: target hosts are tiny (<= 1 vCPU, 512 MB - 1 GB).
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-XX:+UseSerialGC","-jar","/app/app.jar"]
