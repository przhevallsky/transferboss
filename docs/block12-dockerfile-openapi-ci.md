# Block 12 — Dockerfile + OpenAPI (Swagger UI) + GitLab CI Pipeline

## Контекст проекта

**TransferHub** — платформа международных денежных переводов. Kotlin + Spring Boot 3.3.x, JDK 21, Gradle Kotlin DSL.

**Sprint 1, Block 12 (финальный).** Blocks 1–10 завершены: полностью работающий Transfer Service с REST API, Outbox Pattern, Redis cache, тестами. Нужно довести до deployable состояния: Docker image, API-документация, CI pipeline.

## Задача

Три независимые подзадачи:
1. **Dockerfile** — production-ready multi-stage build
2. **springdoc-openapi** — Swagger UI для интерактивного тестирования API
3. **GitLab CI** — реальные stages вместо заглушек Sprint 0

---

# Часть 1: Dockerfile

## Создать: `services/transfer-service/Dockerfile`

```dockerfile
# ============================================================
# Stage 1: BUILD
# Собираем приложение в контейнере с полным JDK и Gradle.
# Результат: fat JAR в /app/build/libs/
# ============================================================
FROM gradle:8.7-jdk21 AS builder

WORKDIR /app

# --- Layer caching strategy ---
# Слои Docker кэшируются. Если файл не изменился — слой берётся из кэша.
# Зависимости меняются редко, код — часто.
# Поэтому: сначала копируем build-файлы → скачиваем зависимости → потом копируем код.
# При изменении только кода зависимости берутся из кэша.

# 1. Gradle wrapper и конфигурация (меняется очень редко)
COPY gradle/ gradle/
COPY gradlew .
COPY gradlew.bat .
COPY settings.gradle.kts .

# 2. Build scripts и version catalog (меняется редко)
COPY build.gradle.kts .
# Если есть version catalog:
# COPY gradle/libs.versions.toml gradle/

# Если mono-repo с несколькими модулями — скопируй корневые файлы:
# COPY settings.gradle.kts .
# COPY buildSrc/ buildSrc/  (если есть convention plugins)

# 3. Скачиваем зависимости отдельным слоем
# --no-daemon: не нужен daemon в Docker build
# dependencies task скачивает все зависимости без компиляции
RUN gradle dependencies --no-daemon || true

# 4. Копируем исходный код (меняется часто — этот слой пересобирается при каждом изменении)
COPY src/ src/

# 5. Собираем JAR
# -x test: тесты уже прошли в CI, не запускаем повторно при сборке образа
RUN gradle bootJar --no-daemon -x test

# ============================================================
# Stage 2: RUNTIME
# Минимальный образ только с JRE и собранным JAR.
# Нет JDK, нет Gradle, нет исходников — уменьшает attack surface и размер.
# ============================================================
FROM eclipse-temurin:21-jre-alpine

# Метаданные
LABEL maintainer="transferhub-team"
LABEL service="transfer-service"

# Создаём непривилегированного пользователя
# Security best practice: если контейнер скомпрометирован,
# атакующий получит права appuser, а не root.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Копируем собранный JAR из builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Меняем владельца
RUN chown -R appuser:appgroup /app

# Переключаемся на непривилегированного пользователя
USER appuser

# Порт приложения
EXPOSE 8080

# Health check: Spring Boot Actuator
# interval=30s: проверять каждые 30 секунд
# timeout=5s: таймаут запроса
# retries=3: после 3 неудач контейнер считается unhealthy
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# JVM options для контейнера:
# -XX:+UseContainerSupport — JVM учитывает memory limits контейнера (default в JDK 21)
# -XX:MaxRAMPercentage=75 — использовать не более 75% доступной контейнеру памяти для heap
# -Djava.security.egd — ускорение генерации UUID/random
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
```

## Создать: `services/transfer-service/.dockerignore`

```
# Не включать в Docker build context (ускоряет сборку, уменьшает контекст)
.git
.gitignore
.idea
.gradle
build/
out/
*.md
docker-compose*.yml
Dockerfile
.dockerignore
src/test/
```

## Проверка Dockerfile

```bash
# Из директории services/transfer-service/
cd services/transfer-service

# Build
docker build -t transfer-service:local .

# Проверить размер
docker images transfer-service:local
# Ожидаемый размер: ~200-250MB (JRE alpine + fat JAR)
# Для сравнения: без multi-stage было бы 800MB+

# Запуск (нужны PostgreSQL и Redis)
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/transferhub \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=postgres \
  -e SPRING_DATA_REDIS_HOST=host.docker.internal \
  transfer-service:local

# Проверить non-root
docker run --rm transfer-service:local whoami
# → appuser

# Проверить health
curl http://localhost:8080/actuator/health
```

## Примечание по mono-repo

Если Transfer Service — часть mono-repo, Dockerfile path может отличаться. Два подхода:

**Подход A:** Dockerfile в корне сервиса, build context = директория сервиса:
```bash
docker build -t transfer-service -f services/transfer-service/Dockerfile services/transfer-service/
```

**Подход B:** Dockerfile в корне сервиса, но нужен доступ к root settings.gradle.kts:
```bash
# Build context = корень repo, Dockerfile указывает путь
docker build -t transfer-service -f services/transfer-service/Dockerfile .
```

Подход зависит от структуры Gradle — если `settings.gradle.kts` в корне mono-repo, нужен подход B. Адаптируй COPY пути в Dockerfile под реальную структуру.

---

# Часть 2: springdoc-openapi (Swagger UI)

## Зависимости

В `build.gradle.kts` Transfer Service добавь:

```kotlin
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
```

Если используется Version Catalog (`gradle/libs.versions.toml`):

```toml
[versions]
springdoc = "2.6.0"

[libraries]
springdoc-openapi-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
```

```kotlin
// build.gradle.kts
implementation(libs.springdoc.openapi.ui)
```

Проверь актуальную версию springdoc — `2.6.x` для Spring Boot 3.3.x.

## Конфигурация в application.yml

```yaml
springdoc:
  api-docs:
    path: /api-docs              # OpenAPI JSON spec endpoint
  swagger-ui:
    path: /swagger-ui            # Swagger UI endpoint
    tags-sorter: alpha
    operations-sorter: method
  default-produces-media-type: application/json
  default-consumes-media-type: application/json
```

## OpenAPI аннотации на контроллере

Добавь аннотации в `TransferController.kt` для красивой документации:

```kotlin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Money transfer operations")
class TransferController(...) {

    @Operation(
        summary = "Create a new transfer",
        description = "Creates a money transfer. Requires X-Idempotency-Key header for duplicate protection."
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Transfer created"),
        ApiResponse(responseCode = "200", description = "Idempotent request — returning cached result"),
        ApiResponse(responseCode = "400", description = "Validation error",
            content = [Content(schema = Schema(implementation = org.springframework.http.ProblemDetail::class))]),
        ApiResponse(responseCode = "422", description = "Business rule violation",
            content = [Content(schema = Schema(implementation = org.springframework.http.ProblemDetail::class))])
    )
    @PostMapping
    fun createTransfer(
        @Valid @RequestBody request: CreateTransferRequest,
        @Parameter(description = "Unique idempotency key (UUID)", required = true)
        @RequestHeader("X-Idempotency-Key") idempotencyKey: UUID,
        @Parameter(description = "Sender ID (temporary, will be from JWT)", required = false)
        @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?
    ): ResponseEntity<TransferResponse> {
        // ... existing code
    }

    @Operation(summary = "Get transfer by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Transfer found"),
        ApiResponse(responseCode = "404", description = "Transfer not found")
    )
    @GetMapping("/{id}")
    fun getTransfer(@PathVariable id: UUID): ResponseEntity<TransferResponse> {
        // ... existing code
    }

    @Operation(summary = "List transfers with cursor-based pagination")
    @GetMapping
    fun listTransfers(
        @Parameter(description = "Sender ID") @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?,
        @Parameter(description = "Opaque cursor from previous response") @RequestParam(required = false) cursor: String?,
        @Parameter(description = "Page size (1-100, default 20)") @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<PaginatedResponse<TransferResponse>> {
        // ... existing code
    }
}
```

## Общая OpenAPI configuration (опционально)

Создай конфигурационный класс для глобальных настроек:

```kotlin
package com.transferhub.transfer.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("TransferHub — Transfer Service API")
                .version("v1")
                .description("REST API for creating and managing international money transfers")
                .contact(Contact().name("Transfer Team").email("transfer-team@transferhub.com"))
        )
        .servers(
            listOf(
                Server().url("http://localhost:8080").description("Local development"),
                Server().url("https://api.staging.transferhub.com").description("Staging")
            )
        )
}
```

## Проверка

```bash
# Swagger UI (интерактивная документация)
open http://localhost:8080/swagger-ui

# OpenAPI JSON spec
curl http://localhost:8080/api-docs

# OpenAPI YAML spec
curl http://localhost:8080/api-docs.yaml
```

Swagger UI позволяет:
- Видеть все endpoints с описаниями
- Отправлять запросы прямо из браузера (Try it out)
- Видеть формат request/response
- Видеть коды ошибок

---

# Часть 3: GitLab CI Pipeline

## Изменить: `.gitlab-ci.yml`

Заменяем заглушки Sprint 0 реальными stages для Transfer Service.

```yaml
# ============================================================
# TransferHub GitLab CI/CD Pipeline
# ============================================================

stages:
  - lint
  - test
  - build
  - publish

# ---- Глобальные переменные ----
variables:
  # Gradle
  GRADLE_OPTS: "-Dorg.gradle.daemon=false -Dorg.gradle.parallel=true"
  GRADLE_USER_HOME: "$CI_PROJECT_DIR/.gradle"

  # Docker
  DOCKER_IMAGE_TAG: "$CI_REGISTRY_IMAGE/transfer-service:$CI_COMMIT_SHORT_SHA"
  DOCKER_IMAGE_LATEST: "$CI_REGISTRY_IMAGE/transfer-service:latest"

# ---- Кэширование Gradle (между pipeline runs) ----
.gradle-cache: &gradle-cache
  cache:
    key: gradle-${CI_COMMIT_REF_SLUG}
    paths:
      - .gradle/caches/
      - .gradle/wrapper/
      - services/transfer-service/build/
    policy: pull-push

# ============================================================
# Stage: LINT — статический анализ и стиль кода
# ============================================================
lint:transfer-service:
  stage: lint
  image: gradle:8.7-jdk21
  <<: *gradle-cache
  script:
    - cd services/transfer-service
    # ktlint: проверка code style (Kotlin official conventions)
    - ./gradlew ktlintCheck --no-daemon || echo "ktlint not configured yet, skipping"
    # detekt: статический анализ Kotlin (аналог SonarQube для Kotlin)
    - ./gradlew detekt --no-daemon || echo "detekt not configured yet, skipping"
  rules:
    - changes:
        - services/transfer-service/**/*
  allow_failure: true  # Sprint 1: не блокируем pipeline из-за lint. Sprint 2: убрать.

# ============================================================
# Stage: TEST — unit + integration tests
# ============================================================
test:transfer-service:
  stage: test
  image: gradle:8.7-jdk21
  <<: *gradle-cache
  services:
    # Testcontainers запускают свои контейнеры, но для GitLab CI
    # нужен Docker-in-Docker или mounted Docker socket.
    # Альтернатива: GitLab services (ниже).
    - name: postgres:16-alpine
      alias: postgres
      variables:
        POSTGRES_DB: transferhub_test
        POSTGRES_USER: test
        POSTGRES_PASSWORD: test
    - name: redis:7-alpine
      alias: redis
  variables:
    # Подключение к GitLab services (вместо Testcontainers для CI)
    SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/transferhub_test"
    SPRING_DATASOURCE_USERNAME: "test"
    SPRING_DATASOURCE_PASSWORD: "test"
    SPRING_DATA_REDIS_HOST: "redis"
    SPRING_DATA_REDIS_PORT: "6379"
    SPRING_PROFILES_ACTIVE: "test"
  script:
    - cd services/transfer-service
    - ./gradlew test --no-daemon
  artifacts:
    when: always
    reports:
      junit:
        - services/transfer-service/build/test-results/test/*.xml
    paths:
      - services/transfer-service/build/reports/tests/
    expire_in: 7 days
  rules:
    - changes:
        - services/transfer-service/**/*

# ============================================================
# Stage: BUILD — сборка JAR
# ============================================================
build:transfer-service:
  stage: build
  image: gradle:8.7-jdk21
  <<: *gradle-cache
  script:
    - cd services/transfer-service
    - ./gradlew bootJar --no-daemon -x test
  artifacts:
    paths:
      - services/transfer-service/build/libs/*.jar
    expire_in: 1 day
  rules:
    - changes:
        - services/transfer-service/**/*

# ============================================================
# Stage: PUBLISH — Docker build + push to GitLab Container Registry
# ============================================================
publish:transfer-service:
  stage: publish
  image: docker:24
  services:
    - docker:24-dind
  dependencies:
    - build:transfer-service
  variables:
    DOCKER_TLS_CERTDIR: "/certs"
  before_script:
    - docker login -u "$CI_REGISTRY_USER" -p "$CI_REGISTRY_PASSWORD" "$CI_REGISTRY"
  script:
    - docker build -t "$DOCKER_IMAGE_TAG" -t "$DOCKER_IMAGE_LATEST" services/transfer-service/
    - docker push "$DOCKER_IMAGE_TAG"
    - docker push "$DOCKER_IMAGE_LATEST"
  rules:
    - if: $CI_COMMIT_BRANCH == "main"
      changes:
        - services/transfer-service/**/*

# ============================================================
# Future stages (Sprint 2+):
# - deploy:staging — Helm upgrade to staging
# - integration-test:staging — smoke tests on staging
# - deploy:production — manual trigger, Helm upgrade to production
# ============================================================
```

## Примечания по CI

### Testcontainers vs GitLab Services

Два подхода для тестов с БД в CI:

**GitLab Services (текущий подход):**
- PostgreSQL и Redis запускаются как GitLab services (сайдкар контейнеры)
- Тесты подключаются через environment variables
- Проще, не нужен Docker-in-Docker
- НО: Testcontainers в integration tests не будут работать без DinD

**Docker-in-Docker (для Testcontainers):**
```yaml
test:transfer-service:
  services:
    - docker:24-dind
  variables:
    DOCKER_HOST: "tcp://docker:2376"
    DOCKER_TLS_CERTDIR: "/certs"
    TESTCONTAINERS_RYUK_DISABLED: "true"
```

Для Sprint 1 используем GitLab Services — проще. Если integration tests используют Testcontainers (Block 10) — нужно либо переключить на DinD, либо создать отдельный test profile без Testcontainers для CI (с GitLab services вместо них).

**Рекомендация:** создай `application-ci.yml` с настройками для GitLab CI services, и используй `SPRING_PROFILES_ACTIVE: "test,ci"` в CI.

### Кэширование

Gradle cache экономит 2-5 минут на каждом pipeline (не скачивает зависимости повторно). `policy: pull-push` — каждый job читает и обновляет кэш.

### Image tagging

`$CI_COMMIT_SHORT_SHA` — уникальный тег для каждого коммита. `latest` — всегда указывает на последнюю сборку из main. В production используется SHA-тег (immutable), не latest.

---

## Проверка результата

### Dockerfile:
1. `docker build -t transfer-service:local services/transfer-service/` — успешная сборка
2. Размер образа < 300MB
3. `docker run --rm transfer-service:local whoami` → `appuser`
4. Приложение стартует внутри контейнера

### Swagger UI:
1. http://localhost:8080/swagger-ui — открывается UI
2. Все 3 endpoints видны (POST, GET/{id}, GET list)
3. "Try it out" работает для каждого endpoint
4. Request/response schemas корректные

### GitLab CI:
1. Push в feature branch → pipeline запускается
2. Stages: lint → test → build (publish — только для main)
3. Test stage: зелёный, JUnit report прикреплён
4. Build stage: JAR артефакт доступен

## Чего НЕ делать

- Не настраивай deploy stages (Helm, Kubernetes) — Sprint 3+
- Не настраивай ktlint/detekt правила детально — Sprint 2
- Не создавай Dockerfile для Pricing Service — Sprint 2 (после Block 11)
