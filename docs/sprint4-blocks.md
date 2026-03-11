# Sprint 4 — Resilience + Feature Flags + WebFlux SSE + Redirect & Retry: Декомпозиция на блоки

## Sprint Goal

Circuit Breaker для внешних вызовов (Pricing gRPC, Identity REST). Feature flags через Unleash. SSE endpoint для real-time статуса переводов через WebFlux + Redis Pub/Sub. Redirect & Retry для сохранения ordering нотификаций.

**Что это даёт:** после Sprint 4 система демонстрирует зрелые паттерны отказоустойчивости — circuit breaker защищает от каскадных отказов, redirect & retry сохраняет ordering при ошибках. Появляется первый реактивный endpoint (WebFlux SSE), feature flags через Unleash, и первый Helm chart для Kubernetes. Milestone M4: Resilient System.

---

## Обзор блоков

| Block | Содержание | Tasks | Зависимости |
|-------|-----------|-------|-------------|
| **B1** | Circuit Breaker: Resilience4j на gRPC (Pricing) и REST (Identity) | S4-T01, S4-T02 | Sprint 2 gRPC client |
| **B2** | Circuit Breaker: fallback-логика + Micrometer метрики + integration test | S4-T03, S4-T04 | B1 |
| **B3** | SSE: WebFlux endpoint + Redis Pub/Sub publisher | S4-T05, S4-T06 | Sprint 3 Kafka status events |
| **B4** | SSE: Integration test (create transfer → Kafka → status update → SSE received) | S4-T07 | B3 |
| **B5** | Unleash: Docker Compose setup + Spring Boot integration | S4-T08, S4-T09 | — |
| **B6** | Unleash: первый feature flag `new-pricing-algorithm` + A/B | S4-T10 | B5 |
| **B7** | Redirect & Retry: main consumer — redirect set + retry topic | S4-T11, S4-T12 | Sprint 3 Notification Gateway |
| **B8** | Redirect & Retry: retry consumer + cleanup redirect set | S4-T13 | B7 |
| **B9** | Redirect & Retry: integration test — ordering preserved after failure | S4-T14 | B8 |
| **B10** | Tech Debt: Helm chart для Transfer Service + Outbox cleanup job | S4-T15, S4-T16 | — |

---

## Зависимости между блоками

```
Circuit Breaker ветка:
B1 (Resilience4j Setup) ──→ B2 (Fallback + Metrics + Test)

SSE ветка:
B3 (WebFlux SSE + Redis Pub/Sub) ──→ B4 (SSE Integration Test)

Unleash ветка:
B5 (Setup + Integration) ──→ B6 (First Feature Flag)

Redirect & Retry ветка:
B7 (Main Consumer + Redirect) ──→ B8 (Retry Consumer) ──→ B9 (Integration Test)

B10 (Helm + Outbox Cleanup) — независим, может делаться параллельно
```

Четыре параллельные ветки:
- **Circuit Breaker ветка:** B1 → B2
- **SSE ветка:** B3 → B4
- **Unleash ветка:** B5 → B6
- **Redirect & Retry ветка:** B7 → B8 → B9

Можно чередовать блоки из разных веток. Рекомендуемый порядок: начать с Circuit Breaker (B1–B2, знакомый стек Spring + хороший quick win), потом SSE (B3–B4, новый реактивный подход), потом Unleash (B5–B6, инфраструктура), потом Redirect & Retry (B7–B9, самая сложная часть), в конце Helm (B10).

---

## Детали каждого блока

### Block 1 — Circuit Breaker: Resilience4j на gRPC и REST

**Сервис:** `services/transfer-service/`

**Контекст:** Transfer Service делает два синхронных вызова при создании перевода: gRPC к Pricing Service (валидация котировки) и REST к Identity Service (KYC check). Если любой из них недоступен или тормозит — запрос зависает, потоки исчерпываются, Transfer Service перестаёт обслуживать даже те запросы, которые не зависят от упавшего сервиса. Circuit Breaker решает эту проблему: после нескольких failures — circuit open, запросы к проблемному сервису отклоняются мгновенно (fast fail), Transfer Service продолжает работать.

**Что делать:**

*Добавить зависимости:*
- `build.gradle.kts`: добавить `io.github.resilience4j:resilience4j-spring-boot3`, `io.github.resilience4j:resilience4j-micrometer`, `io.github.resilience4j:resilience4j-kotlin`
- Resilience4j версии 2.x — совместим со Spring Boot 3.x

*Circuit Breaker на gRPC вызов Transfer → Pricing:*
- В классе, который вызывает `PricingService.ValidateQuote()` (gRPC client) — обернуть вызов в circuit breaker
- Конфигурация в `application.yml`:
  ```yaml
  resilience4j:
    circuitbreaker:
      instances:
        pricing-service:
          slidingWindowType: COUNT_BASED
          slidingWindowSize: 10           # оцениваем последние 10 вызовов
          failureRateThreshold: 50        # если 50% из 10 вызовов failed → circuit open
          waitDurationInOpenState: 30s    # через 30 сек → half-open, пропускаем пробный вызов
          permittedNumberOfCallsInHalfOpenState: 3  # 3 пробных вызова в half-open
          minimumNumberOfCalls: 5         # не открываем circuit, пока не было минимум 5 вызовов
          slowCallRateThreshold: 80       # если 80% вызовов медленнее slowCallDurationThreshold → open
          slowCallDurationThreshold: 2s   # вызов считается «медленным» если > 2 сек
          recordExceptions:
            - io.grpc.StatusRuntimeException
            - java.util.concurrent.TimeoutException
          ignoreExceptions:
            - com.transferhub.transfer.exception.QuoteExpiredException  # бизнес-ошибка, не failure
  ```
- Реализация: аннотация `@CircuitBreaker(name = "pricing-service", fallbackMethod = "pricingFallback")` на методе, либо программный подход через `CircuitBreakerRegistry` (предпочтительнее для gRPC, где аннотация может не перехватить gRPC-specific exceptions корректно)
- Программный подход:
  ```kotlin
  @Service
  class PricingClientService(
      private val pricingStub: PricingServiceGrpc.PricingServiceBlockingStub,
      private val circuitBreakerRegistry: CircuitBreakerRegistry
  ) {
      private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("pricing-service")

      fun validateQuote(quoteId: String): QuoteResponse {
          return circuitBreaker.executeSupplier {
              pricingStub
                  .withDeadlineAfter(3, TimeUnit.SECONDS)  // timeout
                  .validateQuote(ValidateQuoteRequest.newBuilder().setQuoteId(quoteId).build())
          }
      }
  }
  ```

*Circuit Breaker на REST вызов Transfer → Identity (KYC check):*
- Аналогичная конфигурация для Identity Service:
  ```yaml
  resilience4j:
    circuitbreaker:
      instances:
        identity-service:
          slidingWindowType: COUNT_BASED
          slidingWindowSize: 10
          failureRateThreshold: 50
          waitDurationInOpenState: 30s
          permittedNumberOfCallsInHalfOpenState: 3
          minimumNumberOfCalls: 5
          slowCallDurationThreshold: 1s   # Identity быстрее, порог ниже
          recordExceptions:
            - org.springframework.web.client.RestClientException
            - java.net.ConnectException
            - java.net.SocketTimeoutException
  ```
- Identity Service сейчас — mock (его владеет Identity Team). Создать минимальный mock-endpoint в Transfer Service (или отдельный mock-модуль):
  - `GET /internal/api/v1/users/{id}/kyc-status` → возвращает JSON с `kyc_level`, `status`, `verified_at`
  - По умолчанию возвращает `status: APPROVED` для всех user_id
  - Для user_id `usr_blocked` возвращает `status: REJECTED` (для тестирования)
  - Для user_id `usr_slow` — задержка 5 секунд (для тестирования slow call threshold)
- Вызов Identity через `RestClient` (Spring Boot 3.x) или `WebClient` с circuit breaker

*Shared конфигурация:*
- Default конфигурация для всех circuit breaker'ов:
  ```yaml
  resilience4j:
    circuitbreaker:
      configs:
        default:
          slidingWindowType: COUNT_BASED
          slidingWindowSize: 10
          failureRateThreshold: 50
          waitDurationInOpenState: 30s
          permittedNumberOfCallsInHalfOpenState: 3
          minimumNumberOfCalls: 5
      instances:
        pricing-service:
          baseConfig: default
          slowCallDurationThreshold: 2s
        identity-service:
          baseConfig: default
          slowCallDurationThreshold: 1s
  ```

**Почему эти параметры:** `slidingWindowSize: 10` и `failureRateThreshold: 50` означают: если 5 из последних 10 вызовов failed → circuit open. Это достаточно чувствительно, чтобы быстро среагировать на проблему, но не слишком агрессивно (один случайный timeout не откроет circuit). `waitDurationInOpenState: 30s` — через 30 секунд пробуем снова. Для финтеха 30 секунд — приемлемый downtime конкретной фичи (создание перевода), лучше чем каскадный отказ всего сервиса.

**Результат:** Transfer Service обёрнут circuit breaker'ами для обоих внешних вызовов. При открытии circuit — быстрый fail вместо ожидания timeout.

---

### Block 2 — Circuit Breaker: Fallback + Метрики + Test

**Сервис:** `services/transfer-service/`

**Контекст:** Circuit Breaker из B1 защищает от каскадных отказов, но без fallback-логики пользователь получает generic 500 при open circuit. Нужно graceful degradation: понятная ошибка или альтернативное поведение.

**Что делать:**

*Fallback для Pricing (circuit open):*
- Когда circuit `pricing-service` open → `CallNotPermittedException` бросается
- Fallback-стратегия: вернуть **кэшированную котировку** из Redis, если она ещё валидна (TTL не истёк). Это возможно, потому что клиент уже получил quote (с quote_id) до создания перевода — можно попробовать достать его из Redis
- Если кэш пуст или expired → вернуть **503 Service Unavailable** с Problem Details:
  ```json
  {
    "type": "https://api.transferhub.com/errors/pricing-unavailable",
    "title": "Pricing Service Unavailable",
    "status": 503,
    "detail": "Unable to validate quote. Please retry in 30 seconds.",
    "instance": "/api/v1/transfers",
    "retryAfter": 30
  }
  ```
- Добавить `Retry-After: 30` header в response (соответствует `waitDurationInOpenState`)
- Реализация:
  ```kotlin
  fun validateQuoteWithFallback(quoteId: String): QuoteResponse {
      return try {
          circuitBreaker.executeSupplier { pricingStub.validateQuote(...) }
      } catch (e: CallNotPermittedException) {
          log.warn("Circuit breaker OPEN for pricing-service, attempting cache fallback", e)
          redisCacheFallback(quoteId)
              ?: throw PricingUnavailableException("Pricing service unavailable, no cached quote")
      }
  }
  ```

*Fallback для Identity (circuit open):*
- Когда circuit `identity-service` open → **reject transfer creation with 503**
- KYC check — обязательный compliance requirement. Нельзя создавать перевод без KYC-проверки, даже с fallback. Это бизнес-правило: «лучше отказать в переводе, чем пропустить неверифицированного пользователя»
- Problem Details:
  ```json
  {
    "type": "https://api.transferhub.com/errors/identity-unavailable",
    "title": "Identity Verification Unavailable",
    "status": 503,
    "detail": "Unable to verify identity. Please retry shortly.",
    "retryAfter": 30
  }
  ```
- На собеседовании: «Для Pricing Service мы реализовали fallback на кэшированную котировку, потому что quote уже был рассчитан и сохранён в Redis. Для Identity Service fallback невозможен — это compliance requirement, мы не можем пропустить KYC-проверку. Поэтому при open circuit → 503 с Retry-After.»

*Centralized exception handling:*
- В `@RestControllerAdvice` добавить handler для `CallNotPermittedException`:
  ```kotlin
  @ExceptionHandler(CallNotPermittedException::class)
  fun handleCircuitBreakerOpen(ex: CallNotPermittedException): ResponseEntity<ProblemDetail> {
      val circuitBreakerName = ex.causingCircuitBreakerName
      log.warn("Circuit breaker open: {}", circuitBreakerName)
      // return appropriate 503 based on which circuit breaker
  }
  ```

*Метрики Resilience4j в Micrometer:*
- Resilience4j + Micrometer auto-configuration (зависимость `resilience4j-micrometer`) автоматически экспортирует метрики:
  - `resilience4j_circuitbreaker_state` (gauge): 0=CLOSED, 1=OPEN, 2=HALF_OPEN — текущее состояние
  - `resilience4j_circuitbreaker_calls_seconds` (timer): latency вызовов, labels: {name, kind=successful|failed|ignored}
  - `resilience4j_circuitbreaker_failure_rate` (gauge): текущий % ошибок
  - `resilience4j_circuitbreaker_slow_call_rate` (gauge): текущий % медленных вызовов
  - `resilience4j_circuitbreaker_not_permitted_calls_total` (counter): сколько вызовов отклонено (circuit open)
- Все метрики доступны через `/actuator/prometheus`
- Для Grafana: в будущем Sprint 5 (observability) будет дашборд, сейчас — только экспорт метрик

*Unit test:*
- Test 1: Pricing circuit breaker opens after failures
  - Mock gRPC stub бросает `StatusRuntimeException(UNAVAILABLE)` 5 раз подряд
  - Verify: 6-й вызов бросает `CallNotPermittedException` (circuit open, не доходит до stub)
  - Verify: fallback возвращает кэшированную котировку (если есть в Redis mock)
- Test 2: Identity circuit breaker → 503
  - Mock Identity REST endpoint возвращает 500 5 раз
  - Verify: следующий вызов createTransfer → 503 с Problem Details
- Test 3: Circuit breaker transitions back to closed
  - Open circuit → wait > 30s (в тесте override `waitDurationInOpenState` на 1s) → half-open → success → closed
- Для тестов: override конфигурации через `@TestPropertySource`:
  ```
  resilience4j.circuitbreaker.instances.pricing-service.wait-duration-in-open-state=1s
  resilience4j.circuitbreaker.instances.pricing-service.minimum-number-of-calls=3
  ```

**Результат:** При отказе внешнего сервиса — graceful degradation: fallback на кэш (Pricing) или информативный 503 (Identity). Метрики доступны для мониторинга. На собеседовании: «Мы использовали Resilience4j circuit breaker с разными стратегиями fallback: для Pricing — cached quote, для Identity — быстрый отказ, потому что KYC — compliance requirement. Метрики состояния circuit breaker экспортируются в Prometheus.»

---

### Block 3 — SSE: WebFlux Endpoint + Redis Pub/Sub

**Сервис:** `services/transfer-service/`

**Контекст:** Сейчас клиент узнаёт статус перевода только через polling (GET /api/v1/transfers/{id}). Для real-time UX нужен push — при каждой смене статуса клиент мгновенно получает обновление. SSE (Server-Sent Events) — стандартный механизм для этого: HTTP-соединение остаётся открытым, сервер отправляет events по мере их появления. Это единственный endpoint в Transfer Service на WebFlux — остальные на Spring MVC. Смешивание MVC + WebFlux в одном приложении поддерживается Spring Boot.

**Что делать:**

*Добавить зависимости:*
- `build.gradle.kts`: добавить `org.springframework.boot:spring-boot-starter-webflux` 
- Transfer Service уже использует `spring-boot-starter-web` (MVC). Spring Boot поддерживает coexistence обоих стеков — MVC остаётся основным, WebFlux используется только для реактивных компонентов
- Важно: НЕ убирать `spring-boot-starter-web`. При наличии обоих стартеров Spring Boot поднимает Tomcat (MVC), а WebFlux-компоненты работают на тех же потоках

*Redis Pub/Sub — publisher (при смене статуса):*
- При каждом обновлении статуса перевода (в Kafka consumer'ах: payment.captured, payout.completed и т.д.) — после обновления в PostgreSQL публиковать в Redis channel
- Redis channel name pattern: `transfer-status:{transfer_id}`
- Message payload (JSON):
  ```json
  {
    "transferId": "txn_abc123",
    "status": "PAYMENT_CAPTURED",
    "previousStatus": "PAYMENT_PENDING",
    "timestamp": "2025-01-15T14:30:00Z",
    "details": {
      "paymentId": "pay_xyz789",
      "capturedAmount": "500.00"
    }
  }
  ```
- Реализация publisher'а:
  ```kotlin
  @Component
  class TransferStatusPublisher(
      private val redisTemplate: StringRedisTemplate
  ) {
      private val objectMapper = ObjectMapper().registerKotlinModule()

      fun publishStatusChange(event: TransferStatusChangedEvent) {
          val channel = "transfer-status:${event.transferId}"
          val message = objectMapper.writeValueAsString(event)
          redisTemplate.convertAndSend(channel, message)
          log.info("Published status change to Redis channel: {}", channel)
      }
  }
  ```
- Вызывать `publishStatusChange()` из того же места, где Transfer Service обновляет статус в PostgreSQL (в Kafka consumer handler'ах)

*Redis Pub/Sub — subscriber (SSE endpoint):*
- Реактивный Redis listener через `ReactiveRedisMessageListenerContainer` (из `spring-boot-starter-data-redis-reactive`):
  ```kotlin
  @Configuration
  class RedisReactiveConfig {
      @Bean
      fun reactiveRedisMessageListenerContainer(
          connectionFactory: ReactiveRedisConnectionFactory
      ): ReactiveRedisMessageListenerContainer {
          return ReactiveRedisMessageListenerContainer(connectionFactory)
      }
  }
  ```

*WebFlux SSE endpoint:*
- `GET /api/v1/transfers/{id}/events` — возвращает `Flux<ServerSentEvent<String>>`
- Реализация контроллера:
  ```kotlin
  @RestController
  @RequestMapping("/api/v1/transfers")
  class TransferSseController(
      private val listenerContainer: ReactiveRedisMessageListenerContainer,
      private val transferService: TransferService,
      private val objectMapper: ObjectMapper
  ) {
      @GetMapping("/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
      fun streamTransferEvents(@PathVariable id: String): Flux<ServerSentEvent<String>> {
          // 1. Сначала отправляем текущий статус (initial event)
          val currentStatus = Mono.fromCallable { transferService.getTransferStatus(id) }
              .subscribeOn(Schedulers.boundedElastic())  // blocking call на elastic pool
              .map { transfer ->
                  ServerSentEvent.builder<String>()
                      .id(UUID.randomUUID().toString())
                      .event("status")
                      .data(objectMapper.writeValueAsString(transfer.toStatusEvent()))
                      .build()
              }

          // 2. Подписываемся на Redis channel для будущих обновлений
          val channelTopic = ChannelTopic("transfer-status:$id")
          val statusUpdates = listenerContainer
              .receive(channelTopic)
              .map { message ->
                  ServerSentEvent.builder<String>()
                      .id(UUID.randomUUID().toString())
                      .event("status")
                      .data(message.message)
                      .build()
              }

          // 3. Heartbeat каждые 30 секунд (чтобы соединение не закрывалось proxy/LB)
          val heartbeat = Flux.interval(Duration.ofSeconds(30))
              .map {
                  ServerSentEvent.builder<String>()
                      .event("heartbeat")
                      .data("{\"timestamp\":\"${Instant.now()}\"}")
                      .build()
              }

          // 4. Объединяем: initial status + live updates + heartbeat
          return currentStatus.flux()
              .concatWith(Flux.merge(statusUpdates, heartbeat))
              .doOnCancel { log.info("SSE connection closed for transfer: {}", id) }
      }
  }
  ```

*Важные нюансы:*
- `subscribeOn(Schedulers.boundedElastic())` — для blocking вызова `transferService.getTransferStatus()` (обращается к PostgreSQL через JDBC). В реактивной цепочке нельзя делать blocking calls на event loop потоках
- Heartbeat: прокси (Nginx Ingress, CloudFlare) закрывают idle-соединения через 60-120 секунд. Heartbeat каждые 30 секунд держит соединение живым
- Client-side: при обрыве соединения SSE стандарт предусматривает автоматический reconnect (browser EventSource делает это из коробки). При reconnect клиент получает текущий статус через initial event
- Authorization: в Sprint 5 (Security) добавится JWT-проверка. Сейчас — без auth

*Docker Compose — Redis уже есть из Sprint 0. Никаких изменений не нужно.*

**Результат:** При подписке на SSE endpoint клиент получает текущий статус и все последующие обновления в real-time. Redis Pub/Sub связывает Kafka consumer (где обновляется статус) с SSE endpoint.

---

### Block 4 — SSE: Integration Test

**Сервис:** `services/transfer-service/`

**Контекст:** SSE + Redis Pub/Sub — нетривиальная реактивная цепочка. Без интеграционного теста легко пропустить проблему (race condition, отсутствие initial event, невалидный JSON).

**Что делать:**

*Integration test с Testcontainers (PostgreSQL + Kafka + Redis):*

- Test 1: SSE returns current status on connect
  - Создать transfer через POST /api/v1/transfers → status=CREATED
  - Подключиться к SSE: GET /api/v1/transfers/{id}/events
  - Verify: первый SSE event содержит `status: CREATED`
  - Использовать `WebTestClient` с поддержкой SSE:
    ```kotlin
    webTestClient.get()
        .uri("/api/v1/transfers/$transferId/events")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus().isOk
        .returnResult<ServerSentEvent<String>>()
        .responseBody
        .take(1)  // берём только первый event
        .collectList()
        .block(Duration.ofSeconds(5))
    ```

- Test 2: SSE receives live status update
  - Создать transfer → подключиться к SSE
  - Publish mock `payments.payment.captured` в Kafka
  - Transfer Service consumer обрабатывает → обновляет PostgreSQL → publish в Redis
  - Verify: SSE клиент получает event с `status: PAYMENT_CAPTURED`
  - Timing: использовать `StepVerifier` или `take(2)` (initial + update) с timeout 10 секунд

- Test 3: Multiple status updates in sequence
  - Создать transfer → подключиться к SSE
  - Последовательно: payment.captured → payout.completed
  - Verify: SSE клиент получает 3 event'а (CREATED + PAYMENT_CAPTURED + COMPLETED) в правильном порядке

- Test 4: SSE for non-existent transfer → 404
  - GET /api/v1/transfers/non-existent/events → 404 Problem Details

*Примечание по тестированию:*
- WebFlux SSE тесты требуют аккуратной работы с таймингами — Kafka consumer обрабатывает event асинхронно, и между публикацией и SSE event может быть задержка 100-500ms
- Использовать `Awaitility` или `StepVerifier.withVirtualTime()` для управления таймингами

**Результат:** 4 интеграционных теста подтверждают корректность SSE pipeline: initial status, live updates, sequencing, error handling.

---

### Block 5 — Unleash: Docker Compose Setup + Spring Boot Integration

**Сервис:** инфраструктура + `services/transfer-service/`

**Контекст:** Feature flags позволяют деплоить незавершённый код, делать canary rollout и быстро откатывать фичи без передеплоя. Unleash — open-source feature flag platform с UI, audit log и SDK для Kotlin/Java. В этом блоке — поднимаем Unleash и интегрируем SDK.

**Что делать:**

*Docker Compose — Unleash:*
- Добавить в `docker-compose.yml`:
  ```yaml
  unleash:
    image: unleashorg/unleash-server:5
    ports:
      - "4242:4242"
    environment:
      DATABASE_URL: "postgres://postgres:postgres@postgres:5432/unleash"
      DATABASE_SSL: "false"
      INIT_ADMIN_API_TOKENS: "default:development.unleash-insecure-api-token"
      INIT_CLIENT_API_TOKENS: "default:development.unleash-insecure-client-token"
    depends_on:
      postgres:
        condition: service_healthy
  ```
- Unleash использует PostgreSQL для хранения — использовать ту же PostgreSQL-инстанцию, но отдельную базу (`unleash`). Добавить создание базы в init-скрипт:
  ```sql
  CREATE DATABASE unleash;
  ```
- `INIT_ADMIN_API_TOKENS` и `INIT_CLIENT_API_TOKENS` — для dev-окружения. В production секреты управляются через Vault
- UI доступен на `http://localhost:4242`, логин admin / unleash4all (default)

*Первичная конфигурация Unleash:*
- После первого запуска — через UI или API создать:
  - Project: `transferhub` (группировка флагов)
  - Environment: `development` (уже есть по умолчанию)
- Это можно автоматизировать через init-скрипт (curl к Unleash API), но для начала достаточно ручной настройки через UI

*Spring Boot Integration:*
- `build.gradle.kts`: добавить зависимость `io.getunleash:unleash-client-java:9.x.x`
- Конфигурация:
  ```yaml
  # application.yml
  unleash:
    api-url: http://localhost:4242/api
    api-token: "default:development.unleash-insecure-client-token"
    app-name: transfer-service
    environment: development
    fetch-toggles-interval: 10  # секунд, как часто SDK запрашивает обновления
  ```
- Bean-конфигурация:
  ```kotlin
  @Configuration
  class UnleashConfig(
      @Value("\${unleash.api-url}") private val apiUrl: String,
      @Value("\${unleash.api-token}") private val apiToken: String,
      @Value("\${unleash.app-name}") private val appName: String,
      @Value("\${unleash.environment}") private val environment: String,
      @Value("\${unleash.fetch-toggles-interval:10}") private val fetchInterval: Long
  ) {
      @Bean
      fun unleash(): Unleash {
          val config = UnleashConfig.builder()
              .unleashAPI("$apiUrl/")
              .apiKey(apiToken)
              .appName(appName)
              .environment(environment)
              .fetchTogglesInterval(fetchInterval)
              .build()
          return DefaultUnleash(config)
      }
  }
  ```
- Verification: при старте Transfer Service в логах должно быть: `Unleash client started successfully`

*Health check:*
- Добавить кастомный health indicator для Unleash (чтобы видеть в /actuator/health):
  ```kotlin
  @Component
  class UnleashHealthIndicator(private val unleash: Unleash) : HealthIndicator {
      override fun health(): Health {
          return if (unleash.more().isReady) {
              Health.up().withDetail("toggles-loaded", true).build()
          } else {
              Health.down().withDetail("toggles-loaded", false).build()
          }
      }
  }
  ```

*Для тестов:*
- В тестовом профиле — использовать `FakeUnleash` (mock из Unleash SDK):
  ```kotlin
  @TestConfiguration
  class TestUnleashConfig {
      @Bean
      @Primary
      fun unleash(): Unleash = FakeUnleash()
  }
  ```
- `FakeUnleash` позволяет включать/выключать флаги в тестах программно

**Результат:** `docker compose up` поднимает Unleash UI на порту 4242. Transfer Service подключается к Unleash и может проверять feature flags. Тестовый профиль использует FakeUnleash.

---

### Block 6 — Unleash: Первый Feature Flag `new-pricing-algorithm`

**Сервисы:** `services/transfer-service/`, `services/pricing-service/`

**Контекст:** Feature flag без реального кейса — бессмысленная инфраструктура. Нужен конкретный пример: A/B между старым и новым алгоритмом расчёта fee. Это классический кейс для feature flags в финтехе — новый алгоритм тестируется на небольшом проценте пользователей, мониторим метрики, при проблемах — мгновенный откат.

**Что делать:**

*Создать feature flag в Unleash:*
- Через UI (http://localhost:4242): Create toggle → `new-pricing-algorithm`
- Type: `Experiment` (A/B testing)
- Strategy: `Gradual Rollout` → 20% (включён для 20% пользователей)
- Stickiness: `userId` (один и тот же пользователь всегда попадает в одну группу)

*Реализация в Pricing Service (или Transfer Service — где рассчитывается fee):*
- Два алгоритма расчёта fee:
  - Old: простой процент от суммы (flat_fee + percentage × amount)
  - New: tiered pricing (разная ставка для разных диапазонов сумм) — более выгодный для мелких переводов
- Pattern Strategy — чистое переключение без if/else в бизнес-логике:
  ```kotlin
  interface FeeCalculator {
      fun calculateFee(amount: BigDecimal, corridor: Corridor): BigDecimal
  }

  @Component
  class LegacyFeeCalculator : FeeCalculator {
      override fun calculateFee(amount: BigDecimal, corridor: Corridor): BigDecimal {
          return corridor.flatFee + (corridor.feePercentage * amount)
      }
  }

  @Component
  class TieredFeeCalculator : FeeCalculator {
      override fun calculateFee(amount: BigDecimal, corridor: Corridor): BigDecimal {
          // tiered: 0-100 → 1%, 100-500 → 0.8%, 500+ → 0.5%
          return when {
              amount <= BigDecimal(100) -> amount * BigDecimal("0.01")
              amount <= BigDecimal(500) -> BigDecimal(100) * BigDecimal("0.01") +
                  (amount - BigDecimal(100)) * BigDecimal("0.008")
              else -> BigDecimal(100) * BigDecimal("0.01") +
                  BigDecimal(400) * BigDecimal("0.008") +
                  (amount - BigDecimal(500)) * BigDecimal("0.005")
          } + corridor.flatFee
      }
  }

  @Service
  class FeeService(
      private val unleash: Unleash,
      private val legacyCalculator: LegacyFeeCalculator,
      private val tieredCalculator: TieredFeeCalculator
  ) {
      fun calculateFee(senderId: String, amount: BigDecimal, corridor: Corridor): BigDecimal {
          val context = UnleashContext.builder()
              .userId(senderId)
              .build()

          val calculator = if (unleash.isEnabled("new-pricing-algorithm", context)) {
              log.info("Using TIERED fee calculator for sender: {}", senderId)
              tieredCalculator
          } else {
              legacyCalculator
          }

          return calculator.calculateFee(amount, corridor)
      }
  }
  ```

*Метрика для A/B сравнения:*
- Добавить Micrometer counter/timer с tag `algorithm`:
  ```kotlin
  val feeCalculationCounter = meterRegistry.counter(
      "pricing_fee_calculation_total",
      "algorithm", if (isNewAlgorithm) "tiered" else "legacy"
  )
  ```
- В Grafana (Sprint 5) можно будет сравнить: среднюю fee, количество расчётов, конверсию quote→transfer для каждой группы

*Unit test:*
- Test с `FakeUnleash`:
  ```kotlin
  @Test
  fun `should use tiered calculator when feature flag enabled`() {
      val fakeUnleash = FakeUnleash()
      fakeUnleash.enable("new-pricing-algorithm")
      // ... verify tiered calculator is used
  }

  @Test
  fun `should use legacy calculator when feature flag disabled`() {
      val fakeUnleash = FakeUnleash()
      fakeUnleash.disable("new-pricing-algorithm")
      // ... verify legacy calculator is used
  }
  ```

**Для собеседования:** «Мы использовали Unleash для feature flags. Первый реальный кейс — A/B тестирование нового алгоритма расчёта fee. Новый tiered-pricing включался для 20% пользователей через Gradual Rollout с stickiness по userId — один пользователь всегда видит одну версию. Мониторили метрики: если средняя fee упадёт или конверсия снизится — откатываем через UI за секунду. Через 2 недели раскатали на 100%.»

**Результат:** Feature flag `new-pricing-algorithm` работает. 20% пользователей видят tiered pricing. Переключение через Unleash UI без передеплоя. Метрика позволяет сравнить алгоритмы.

---

### Block 7 — Redirect & Retry: Main Consumer + Redirect Set

**Сервис:** `services/transfer-service/` (Notification consumer модуль)

**Контекст:** В Sprint 3 реализован @RetryableTopic — при ошибке доставки нотификации event уходит в retry-топик с backoff. Но @RetryableTopic не сохраняет ordering: если Event A для transfer_123 фейлится и уходит в retry, а Event B для того же transfer_123 успешно отправляется — пользователь получает нотификации не в том порядке (например, «transfer completed» раньше «payment captured»). Redirect & Retry решает эту проблему: если одно событие для transfer_id фейлится, все последующие события для этого transfer_id тоже уходят в retry (redirect), пока проблемное событие не будет обработано.

**Важно:** Redirect & Retry — это **замена** @RetryableTopic из Sprint 3 для notification consumer. Мы НЕ используем оба механизма одновременно на одном consumer. @RetryableTopic остаётся для тех consumer'ов, где ordering не критичен (например, analytics events). Для notification — переключаемся на Redirect & Retry.

**Что делать:**

*Убрать @RetryableTopic с notification consumer:*
- Удалить аннотацию `@RetryableTopic` с consumer'а `notification.delivery`
- Оставить обычный `@KafkaListener` — retry-логику будем реализовывать вручную

*Создать Kafka-топики:*
- `notification.delivery.retry` — retry-топик для redirected events
- `notification.delivery.dlt` — Dead Letter Topic (после исчерпания retry)
- Добавить в Docker Compose init-скрипт создание этих топиков (или через AdminClient в коде)

*Redirect Set — in-memory collection:*
- `ConcurrentHashMap<String, Boolean>` для хранения redirected transfer_id'ов
- Почему in-memory, а не Redis: redirect set нужен только в рамках одного инстанса consumer'а. Каждый consumer-инстанс обрабатывает свой набор партиций, и redirect-состояние привязано к партициям, которые обрабатывает этот инстанс. При ребалансировке (партиции перемещаются) — redirect set сбрасывается, и retry consumer продолжит обработку
- Для Production с высокими требованиями к durability можно перенести в Redis — но это overengineering для текущего масштаба

*Main consumer логика:*
```kotlin
@Component
class NotificationDeliveryConsumer(
    private val notificationSender: NotificationSender,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    // transfer_id → redirected (true = все события этого transfer'а уходят в retry)
    private val redirectSet = ConcurrentHashMap<String, Boolean>()

    @KafkaListener(
        topics = ["notification.delivery"],
        groupId = "notification-delivery-consumer"
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val event = objectMapper.readValue(record.value(), NotificationEvent::class.java)
        val transferId = event.transferId

        // 1. Check redirect: если transfer_id в redirect set → сразу в retry
        if (redirectSet.containsKey(transferId)) {
            log.info("Transfer {} is in redirect set, sending to retry topic", transferId)
            sendToRetryTopic(record, event)
            return
        }

        // 2. Попытка доставки
        try {
            notificationSender.send(event)
            log.info("Notification delivered for transfer: {}, event: {}", transferId, event.eventType)
        } catch (e: Exception) {
            log.warn("Delivery failed for transfer: {}, redirecting", transferId, e)

            // 3. При ошибке: добавить в redirect set + отправить в retry topic
            redirectSet[transferId] = true
            sendToRetryTopic(record, event)
        }
    }

    private fun sendToRetryTopic(record: ConsumerRecord<String, String>, event: NotificationEvent) {
        val retryRecord = ProducerRecord(
            "notification.delivery.retry",
            record.key(),  // key = transfer_id (сохраняем ordering в retry-топике)
            record.value()
        )
        // Добавляем header с retry count и original timestamp
        retryRecord.headers()
            .add("retry-count", "0".toByteArray())
            .add("original-timestamp", Instant.now().toString().toByteArray())
        kafkaTemplate.send(retryRecord)
    }

    // Вызывается retry consumer'ом при успешной обработке всех событий для transfer_id
    fun clearRedirect(transferId: String) {
        redirectSet.remove(transferId)
        log.info("Cleared redirect for transfer: {}", transferId)
    }
}
```

*Redirect check — перед отправкой:*
- Шаг 1 в consume(): `if (redirectSet.containsKey(transferId))` — если transfer_id уже в redirect set (предыдущее событие для этого transfer'а фейлилось), текущее событие тоже уходит в retry, даже если оно само по себе нормальное. Это гарантирует ordering
- Ordering в retry-топике: key = transfer_id, Kafka гарантирует ordering внутри партиции

**Результат:** При ошибке доставки нотификации для transfer_123 — все последующие события для transfer_123 redirect'ятся в retry topic, сохраняя порядок. Основной consumer не блокируется — события других transfer'ов обрабатываются нормально.

---

### Block 8 — Redirect & Retry: Retry Consumer + Cleanup

**Сервис:** `services/transfer-service/`

**Контекст:** Events в retry-топике ждут обработки. Нужен отдельный consumer, который последовательно обрабатывает retry-events и при успехе очищает redirect set.

**Что делать:**

*Retry consumer:*
```kotlin
@Component
class NotificationRetryConsumer(
    private val notificationSender: NotificationSender,
    private val mainConsumer: NotificationDeliveryConsumer,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    companion object {
        const val MAX_RETRIES = 5
        val RETRY_DELAYS = listOf(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofHours(1)
        )
    }

    @KafkaListener(
        topics = ["notification.delivery.retry"],
        groupId = "notification-retry-consumer",
        properties = [
            "max.poll.records=1"  // обрабатываем по одному для контроля
        ]
    )
    fun consumeRetry(record: ConsumerRecord<String, String>) {
        val event = objectMapper.readValue(record.value(), NotificationEvent::class.java)
        val retryCount = record.headers()
            .lastHeader("retry-count")
            ?.let { String(it.value()).toInt() } ?: 0

        // Backoff delay
        if (retryCount > 0 && retryCount < RETRY_DELAYS.size) {
            val delay = RETRY_DELAYS[retryCount - 1]
            log.info("Retry #{} for transfer: {}, waiting {}s", retryCount, event.transferId, delay.seconds)
            // В реальной системе backoff реализуется через scheduled re-publish
            // или через партиционирование retry-топиков по delay (как Spring @RetryableTopic)
            // Для простоты — Thread.sleep (в production заменить на non-blocking)
            Thread.sleep(delay.toMillis().coerceAtMost(5000))  // cap for dev
        }

        try {
            notificationSender.send(event)
            log.info("Retry successful for transfer: {}", event.transferId)

            // Успех: очищаем redirect set для этого transfer_id
            mainConsumer.clearRedirect(event.transferId)

        } catch (e: Exception) {
            val newRetryCount = retryCount + 1
            if (newRetryCount >= MAX_RETRIES) {
                // Исчерпаны все попытки → DLT
                log.error("Max retries exhausted for transfer: {}, sending to DLT", event.transferId, e)
                sendToDlt(record, event, newRetryCount)
                mainConsumer.clearRedirect(event.transferId)  // очищаем redirect, DLT = terminal
            } else {
                // Ещё есть попытки → обратно в retry topic с инкрементированным счётчиком
                log.warn("Retry #{} failed for transfer: {}, re-queuing", newRetryCount, event.transferId, e)
                requeue(record, event, newRetryCount)
            }
        }
    }

    private fun requeue(record: ConsumerRecord<String, String>, event: NotificationEvent, retryCount: Int) {
        val retryRecord = ProducerRecord(
            "notification.delivery.retry",
            record.key(),
            record.value()
        )
        retryRecord.headers().add("retry-count", retryCount.toString().toByteArray())
        kafkaTemplate.send(retryRecord)
    }

    private fun sendToDlt(record: ConsumerRecord<String, String>, event: NotificationEvent, retryCount: Int) {
        val dltRecord = ProducerRecord(
            "notification.delivery.dlt",
            record.key(),
            record.value()
        )
        dltRecord.headers()
            .add("retry-count", retryCount.toString().toByteArray())
            .add("failure-reason", "max retries exhausted".toByteArray())
        kafkaTemplate.send(dltRecord)

        // Метрика
        Metrics.counter("kafka_dlt_messages_total", "topic", "notification.delivery").increment()
    }
}
```

*Sequential processing — ключевой момент:*
- `max.poll.records=1` — retry consumer обрабатывает по одному event'у. Это проще и гарантирует, что events одного transfer'а обрабатываются последовательно (при условии, что они в одной партиции, что обеспечивается key=transfer_id)
- Для production с высоким throughput можно обрабатывать батчами с группировкой по transfer_id

*DLT consumer (аналогично Sprint 3):*
- `@KafkaListener(topics = ["notification.delivery.dlt"])` — логирование + метрика
- DLT messages требуют ручного разбора (manual replay или расследование root cause)

*Cleanup redirect set:*
- При `clearRedirect(transferId)` — удаляем transfer_id из in-memory redirect set
- После очистки — следующие events для этого transfer_id в основном топике пойдут напрямую (не через redirect)
- Edge case: если между redirect и clear пришли новые events в основной топик — они были redirected. Retry consumer обработает их последовательно. Только после полной обработки всех redirected events и успешного retry — redirect очищается

**Результат:** Retry consumer обрабатывает redirected events последовательно с backoff. При успехе — очищает redirect set. При исчерпании попыток — DLT + метрика. Ordering сохранён.

---

### Block 9 — Redirect & Retry: Integration Test

**Сервис:** `services/transfer-service/`

**Контекст:** Redirect & Retry — самый сложный паттерн обработки ошибок в проекте. Без integration test'а невозможно быть уверенным в корректности ordering-гарантий.

**Что делать:**

*Integration test с Testcontainers (Kafka + Redis):*

- Test 1: Happy path — no redirect, direct delivery
  - Publish notification event для transfer_123 в `notification.delivery`
  - NotificationSender mock → success
  - Verify: event НЕ в retry topic, НЕ в DLT
  - Verify: notification delivered (mock called)

- Test 2: First event fails → redirect → second event redirected → retry succeeds → ordering preserved
  - Publish Event A (transfer_123, status=PAYMENT_CAPTURED) → NotificationSender бросает exception
  - Verify: Event A в `notification.delivery.retry`
  - Verify: transfer_123 в redirect set
  - Publish Event B (transfer_123, status=COMPLETED) → redirect check → redirect
  - Verify: Event B в `notification.delivery.retry` (redirected, not delivered directly)
  - Configure NotificationSender mock: succeed on next calls
  - Wait for retry consumer to process Event A → success
  - Wait for retry consumer to process Event B → success
  - Verify: redirect set cleared for transfer_123
  - **Verify ordering:** Event A delivered before Event B (это ключевая проверка!)

- Test 3: Redirect does NOT affect other transfers
  - Publish Event A (transfer_123) → fail → redirect
  - Publish Event X (transfer_456) → success (no redirect for 456)
  - Verify: Event X delivered directly, не попал в retry
  - Verify: transfer_456 NOT in redirect set

- Test 4: Max retries exhausted → DLT
  - Publish Event A (transfer_123) → NotificationSender always throws
  - Wait for retry consumer: 5 attempts
  - Verify: Event A в `notification.delivery.dlt`
  - Verify: redirect set cleared (DLT = terminal)
  - Verify: метрика `kafka_dlt_messages_total` инкрементирована

*Примечание по таймингам:*
- Override retry delays в тестах: `RETRY_DELAYS = listOf(100ms, 200ms, 300ms, 400ms, 500ms)` — через test-specific конфигурацию
- Использовать `Awaitility.await().atMost(30, SECONDS).until { ... }` для проверки асинхронных условий

*Helper для отслеживания ordering:*
```kotlin
class OrderTrackingNotificationSender : NotificationSender {
    val deliveredEvents = CopyOnWriteArrayList<NotificationEvent>()
    var shouldFail = AtomicBoolean(false)

    override fun send(event: NotificationEvent) {
        if (shouldFail.get()) throw RuntimeException("Simulated failure")
        deliveredEvents.add(event)
    }
}
```

**Результат:** 4 интеграционных теста покрывают: happy path, ordering preservation, isolation between transfers, DLT fallback. Это safety net для всех будущих изменений в notification delivery pipeline.

---

### Block 10 — Tech Debt: Helm Chart + Outbox Cleanup

**Сервисы:** `services/transfer-service/`, `services/outbox-service/`

**Контекст:** Два tech debt задачи: Transfer Service нуждается в Helm chart для деплоя в Kubernetes (до сих пор деплоили через Docker Compose), и outbox-таблица растёт бесконечно — нужен cleanup job.

**Что делать:**

*Helm Chart для Transfer Service:*
- Создать директорию `infra/helm/transfer-service/`
- Chart.yaml:
  ```yaml
  apiVersion: v2
  name: transfer-service
  version: 0.1.0
  appVersion: "1.0.0"
  description: Core transfer lifecycle service
  ```
- templates/deployment.yaml:
  ```yaml
  apiVersion: apps/v1
  kind: Deployment
  metadata:
    name: {{ .Release.Name }}-transfer-service
    labels:
      app: transfer-service
  spec:
    replicas: {{ .Values.replicaCount }}
    strategy:
      type: RollingUpdate
      rollingUpdate:
        maxUnavailable: 0
        maxSurge: 1
    selector:
      matchLabels:
        app: transfer-service
    template:
      metadata:
        labels:
          app: transfer-service
        annotations:
          prometheus.io/scrape: "true"
          prometheus.io/port: "8080"
          prometheus.io/path: "/actuator/prometheus"
      spec:
        securityContext:
          runAsNonRoot: true
          runAsUser: 1000
        containers:
          - name: transfer-service
            image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
            ports:
              - containerPort: 8080
            envFrom:
              - configMapRef:
                  name: {{ .Release.Name }}-config
              - secretRef:
                  name: {{ .Release.Name }}-secrets
            resources:
              requests:
                memory: {{ .Values.resources.requests.memory }}
                cpu: {{ .Values.resources.requests.cpu }}
              limits:
                memory: {{ .Values.resources.limits.memory }}
                cpu: {{ .Values.resources.limits.cpu }}
            livenessProbe:
              httpGet:
                path: /actuator/health/liveness
                port: 8080
              initialDelaySeconds: 30
              periodSeconds: 10
              failureThreshold: 3
            readinessProbe:
              httpGet:
                path: /actuator/health/readiness
                port: 8080
              initialDelaySeconds: 15
              periodSeconds: 5
              failureThreshold: 3
            startupProbe:
              httpGet:
                path: /actuator/health/liveness
                port: 8080
              failureThreshold: 30
              periodSeconds: 1
              # 30 × 1 = 30 sec max startup time
  ```
- templates/service.yaml:
  ```yaml
  apiVersion: v1
  kind: Service
  metadata:
    name: {{ .Release.Name }}-transfer-service
  spec:
    selector:
      app: transfer-service
    ports:
      - port: 8080
        targetPort: 8080
    type: ClusterIP
  ```
- templates/configmap.yaml:
  ```yaml
  apiVersion: v1
  kind: ConfigMap
  metadata:
    name: {{ .Release.Name }}-config
  data:
    SPRING_PROFILES_ACTIVE: {{ .Values.spring.profile }}
    SPRING_DATASOURCE_URL: {{ .Values.database.url }}
    SPRING_KAFKA_BOOTSTRAP_SERVERS: {{ .Values.kafka.bootstrapServers }}
    REDIS_HOST: {{ .Values.redis.host }}
    REDIS_PORT: {{ .Values.redis.port | quote }}
    UNLEASH_API_URL: {{ .Values.unleash.apiUrl }}
  ```
- templates/hpa.yaml:
  ```yaml
  apiVersion: autoscaling/v2
  kind: HorizontalPodAutoscaler
  metadata:
    name: {{ .Release.Name }}-transfer-service
  spec:
    scaleTargetRef:
      apiVersion: apps/v1
      kind: Deployment
      name: {{ .Release.Name }}-transfer-service
    minReplicas: {{ .Values.hpa.minReplicas }}
    maxReplicas: {{ .Values.hpa.maxReplicas }}
    metrics:
      - type: Resource
        resource:
          name: cpu
          target:
            type: Utilization
            averageUtilization: {{ .Values.hpa.targetCPUUtilization }}
  ```
- values.yaml (default / dev):
  ```yaml
  replicaCount: 2
  image:
    repository: registry.gitlab.com/transferhub/transfer-service
    tag: latest
  resources:
    requests:
      memory: "512Mi"
      cpu: "250m"
    limits:
      memory: "1Gi"
      cpu: "1000m"
  spring:
    profile: dev
  database:
    url: "jdbc:postgresql://postgres:5432/transferhub"
  kafka:
    bootstrapServers: "kafka:9092"
  redis:
    host: "redis"
    port: 6379
  unleash:
    apiUrl: "http://unleash:4242/api"
  hpa:
    minReplicas: 2
    maxReplicas: 8
    targetCPUUtilization: 60
  ```
- values-staging.yaml, values-production.yaml — отдельные файлы с production-specific настройками (больше ресурсов, другие URLs, больше реплик)

*Probes — почему именно такие:*
- **livenessProbe:** проверяет /actuator/health/liveness — «жив ли процесс». НЕ проверяет внешние зависимости (PostgreSQL, Kafka). Если PostgreSQL упал — перезапуск Transfer Service не поможет, а создаст каскад рестартов
- **readinessProbe:** проверяет /actuator/health/readiness — «готов ли принимать трафик». Проверяет критичные зависимости: PostgreSQL connection pool, Kafka consumer connected. Если PostgreSQL недоступна → Pod выходит из Service → трафик не приходит
- **startupProbe:** JVM + Spring context = 15-25 сек старта. `failureThreshold: 30 × periodSeconds: 1 = 30 сек` на старт, потом liveness/readiness начинают проверять

*Outbox Cleanup Job:*
- В Outbox Service или Transfer Service (где доступна outbox-таблица) добавить scheduled job:
  ```kotlin
  @Component
  class OutboxCleanupJob(
      private val jdbcTemplate: JdbcTemplate
  ) {
      @Scheduled(cron = "0 0 3 * * *")  // каждый день в 3:00 AM
      fun cleanupProcessedEvents() {
          val cutoff = Instant.now().minus(7, ChronoUnit.DAYS)
          val deleted = jdbcTemplate.update(
              "DELETE FROM outbox_events WHERE status = 'SENT' AND processed_at < ?",
              Timestamp.from(cutoff)
          )
          log.info("Outbox cleanup: deleted {} processed events older than 7 days", deleted)
          Metrics.counter("outbox_cleanup_deleted_total").increment(deleted.toDouble())
      }
  }
  ```
- Только записи со статусом `SENT` (уже опубликованные в Kafka) и старше 7 дней
- `@EnableScheduling` — добавить на Application class, если ещё нет
- Метрика `outbox_cleanup_deleted_total` для мониторинга
- В production: если outbox-таблица огромная (>1M строк) — удалять батчами по 1000, чтобы не блокировать таблицу на долго:
  ```sql
  DELETE FROM outbox_events
  WHERE id IN (
      SELECT id FROM outbox_events
      WHERE status = 'SENT' AND processed_at < ?
      LIMIT 1000
  )
  ```

**Результат:** Helm chart позволяет деплоить Transfer Service в Kubernetes через `helm install`. Outbox cleanup предотвращает бесконечный рост таблицы. На собеседовании: «Мы использовали Helm charts для стандартизации деплоя. Каждый сервис имеет Deployment с rolling update (maxUnavailable=0), три типа probes (liveness, readiness, startup), HPA по CPU, ConfigMap для конфигурации. Resource requests рассчитаны: 512Mi request — это heap (256MB) + metaspace + overhead JVM.»

---

## Зависимости между блоками (детально)

```
                    ┌─────────────────────────────────────────────────┐
                    │         CIRCUIT BREAKER ВЕТКА                    │
                    │                                                 │
                    │  B1 (Resilience4j: gRPC + REST)                 │
                    │    ↓                                            │
                    │  B2 (Fallback + Metrics + Test)                 │
                    └─────────────────────────────────────────────────┘

                    ┌─────────────────────────────────────────────────┐
                    │              SSE ВЕТКА                           │
                    │                                                 │
                    │  B3 (WebFlux SSE + Redis Pub/Sub)               │
                    │    ↓                                            │
                    │  B4 (SSE Integration Test)                      │
                    └─────────────────────────────────────────────────┘

                    ┌─────────────────────────────────────────────────┐
                    │            UNLEASH ВЕТКА                         │
                    │                                                 │
                    │  B5 (Setup + Spring Boot Integration)           │
                    │    ↓                                            │
                    │  B6 (Feature Flag: new-pricing-algorithm)       │
                    └─────────────────────────────────────────────────┘

                    ┌─────────────────────────────────────────────────┐
                    │         REDIRECT & RETRY ВЕТКА                   │
                    │                                                 │
                    │  B7 (Main Consumer + Redirect Set)              │
                    │    ↓                                            │
                    │  B8 (Retry Consumer + Cleanup)                  │
                    │    ↓                                            │
                    │  B9 (Integration Test: Ordering Preserved)      │
                    └─────────────────────────────────────────────────┘

                    B10 (Helm Chart + Outbox Cleanup) — независим
```

## Рекомендуемый порядок работы

Четыре ветки можно чередовать. Рекомендуемая последовательность для баланса между прогрессом и переключением контекста:

1. **B1** — Circuit Breaker setup (знакомый Spring Boot, good quick win, закладывает паттерн для всех внешних вызовов)
2. **B2** — Circuit Breaker fallback + metrics (завершаем ветку, результат сразу виден)
3. **B5** — Unleash setup (инфраструктура, Docker Compose, пока свежий взгляд на инфру)
4. **B3** — WebFlux SSE + Redis Pub/Sub (новый реактивный подход — самый интересный и образовательный блок)
5. **B6** — Unleash feature flag (быстрый блок, завершаем Unleash-ветку)
6. **B4** — SSE Integration Test (завершаем SSE-ветку)
7. **B7** — Redirect & Retry main consumer (самый сложный паттерн — начинаем со свежей головой)
8. **B8** — Redirect & Retry retry consumer (продолжаем, пока в контексте)
9. **B9** — Redirect & Retry integration test (подтверждаем ordering)
10. **B10** — Helm + Outbox Cleanup (tech debt, завершающий аккорд)

---

## Новые Kafka-топики в Sprint 4

| Топик | Producer | Consumer | Назначение |
|-------|----------|----------|-----------|
| `notification.delivery.retry` | Notification Main Consumer | Notification Retry Consumer | Redirected events для сохранения ordering |
| `notification.delivery.dlt` | Notification Retry Consumer | DLT Handler (log + metric) | Dead Letter Topic для notification delivery |

*Примечание:* топики `notification.commands-retry-*` и `notification.commands-dlt` из Sprint 3 (@RetryableTopic) удаляются / deprecated, т.к. Redirect & Retry заменяет @RetryableTopic для notification consumer.

---

## Новые инфраструктурные компоненты

| Компонент | Порт | Назначение |
|-----------|------|-----------|
| Unleash Server | 4242 | Feature flag management UI + API |

---

## Итого Sprint 4

| Метрика | Значение |
|---------|----------|
| Блоков | 10 |
| Новые паттерны | Circuit Breaker (Resilience4j), SSE (WebFlux), Feature Flags (Unleash), Redirect & Retry Topic |
| Новые технологии | Resilience4j, Spring WebFlux (SSE only), Redis Pub/Sub, Unleash |
| Новый реактивный endpoint | GET /api/v1/transfers/{id}/events (SSE) |
| Helm chart | Transfer Service (Deployment, Service, ConfigMap, HPA, Probes) |
| Kafka-топиков новых | 2 (notification.delivery.retry, notification.delivery.dlt) |
| Тесты | Circuit breaker unit tests, SSE integration tests (4), Redirect & Retry integration tests (4) |
| Feature flags | 1 (`new-pricing-algorithm` — A/B tiered pricing) |
| Tech Debt закрыто | Outbox cleanup job, Helm chart |

---

## Формулировки для собеседования (Sprint 4 highlights)

**Circuit Breaker:**
> «Мы использовали Resilience4j circuit breaker для защиты Transfer Service от каскадных отказов. На gRPC-вызове к Pricing Service — при 50% failure rate за 10 вызовов circuit открывается, fallback — cached quote из Redis. На REST-вызове к Identity Service — fallback невозможен (compliance requirement), возвращаем 503 с Retry-After. Метрики состояния circuit breaker экспортируются в Prometheus через Micrometer.»

**SSE + WebFlux:**
> «Для real-time статусов мы реализовали SSE endpoint на Spring WebFlux — это единственный реактивный endpoint в Transfer Service, остальные на MVC. Redis Pub/Sub связывает Kafka consumer (который обновляет статус) с SSE endpoint. При подключении клиент получает текущий статус (initial event), затем live-updates. Heartbeat каждые 30 секунд держит соединение через прокси.»

**Feature Flags:**
> «Мы использовали self-hosted Unleash для feature flags. Первый кейс — A/B тестирование нового алгоритма расчёта fee: tiered pricing для 20% пользователей через Gradual Rollout. Stickiness по userId — один пользователь всегда видит одну версию. Переключение через UI без деплоя.»

**Redirect & Retry:**
> «Стандартный @RetryableTopic не сохраняет ordering — если Event A фейлится и уходит в retry, Event B для того же transfer'а может быть доставлен раньше. Мы реализовали Redirect & Retry: при ошибке Event A → transfer_id добавляется в redirect set, все последующие events для этого transfer'а перенаправляются в retry topic. Retry consumer обрабатывает последовательно, при успехе очищает redirect set. Ordering гарантирован, основной consumer не блокируется.»
