# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy Gradle wrapper and configuration (cacheable layer)
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts gradle/libs.versions.toml ./

# Copy service build file
COPY services/outbox-service/build.gradle.kts services/outbox-service/

# Download dependencies (cacheable layer)
RUN ./gradlew :services:outbox-service:dependencies --no-daemon || true

# Copy source code
COPY services/outbox-service/src/ services/outbox-service/src/

# Build
RUN ./gradlew :services:outbox-service:bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /build/services/outbox-service/build/libs/*.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8081

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
