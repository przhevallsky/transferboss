# ── Stage 1: Build ──────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY gradle/               gradle/
COPY gradlew               gradlew
COPY gradlew.bat           gradlew.bat
COPY build.gradle.kts      build.gradle.kts
COPY settings.gradle.kts   settings.gradle.kts
COPY gradle/libs.versions.toml gradle/libs.versions.toml

COPY services/transfer-service/ services/transfer-service/

RUN chmod +x gradlew && \
    ./gradlew :services:transfer-service:bootJar --no-daemon -x test

# ── Stage 2: Runtime ───────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/services/transfer-service/build/libs/*.jar app.jar

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
