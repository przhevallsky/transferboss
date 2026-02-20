# ── Stage 1: Build ──────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Layer caching: copy build config first (changes rarely)
COPY gradle/               gradle/
COPY gradlew               gradlew
COPY gradlew.bat           gradlew.bat
COPY build.gradle.kts      build.gradle.kts
COPY settings.gradle.kts   settings.gradle.kts

# Download dependencies as a separate layer (cached unless build files change)
COPY services/transfer-service/build.gradle.kts services/transfer-service/build.gradle.kts
RUN chmod +x gradlew && \
    ./gradlew :services:transfer-service:dependencies --no-daemon || true

# Copy source code (changes frequently — this layer rebuilds often)
COPY services/transfer-service/src/ services/transfer-service/src/

RUN ./gradlew :services:transfer-service:bootJar --no-daemon -x test

# ── Stage 2: Runtime ───────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="transferhub-team"
LABEL service="transfer-service"

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/services/transfer-service/build/libs/*.jar app.jar

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
