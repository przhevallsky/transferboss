# Stage 1: Build
FROM gradle:8.7-jdk21 AS build
WORKDIR /app

COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./
COPY services/pricing-service/build.gradle.kts services/pricing-service/
COPY services/pricing-service/src/main/proto/ services/pricing-service/src/main/proto/

# Download dependencies (cacheable layer)
RUN gradle :services:pricing-service:dependencies --no-daemon || true

# Copy source code and build
COPY services/pricing-service/ services/pricing-service/
RUN gradle :services:pricing-service:installDist --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /app/services/pricing-service/build/install/pricing-service/ ./

USER appuser

EXPOSE 8082 9090

ENTRYPOINT ["./bin/pricing-service"]
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+UseStringDeduplication"
