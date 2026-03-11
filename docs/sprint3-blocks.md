оке# Sprint 3 — Saga + Go Notification + Retry/DLQ: Декомпозиция на блоки

## Sprint Goal

Полный lifecycle перевода через Saga (create → payment → payout → complete). Retry-механизмы для ошибок. Notification Gateway на Go отправляет push/SMS.

**Что это даёт:** после Sprint 3 система реализует полный бизнес-сценарий — от создания перевода до завершения через choreography-based Saga с компенсациями. Появляется первый polyglot-сервис на Go. Retry/DLQ обеспечивают устойчивость к ошибкам в асинхронной обработке.

---

## Обзор блоков

| Block | Содержание | Tasks | Зависимости |
|-------|-----------|-------|-------------|
| **B1** | Saga: Payment Step — Transfer публикует payment.requested, Mock Payment Service отвечает | S3-T01, S3-T02 | Sprint 2 outbox + Kafka |
| **B2** | Saga: Payout Step — Mock Payout Service + Transfer consumes payout events | S3-T03, S3-T04 | B1 |
| **B3** | Saga: Compensation — failure paths (payment.failed, payout.failed → refund) | S3-T05 | B2 |
| **B4** | Saga: Integration tests — full happy path + failure scenarios | S3-T06 | B3 |
| **B5** | Go Notification Gateway: скелет — project setup + Kafka consumer | S3-T10, S3-T11 | Sprint 2 Kafka topics |
| **B6** | Go Notification Gateway: delivery adapters + graceful shutdown + Prometheus metrics | S3-T12, S3-T15, S3-T13 | B5 |
| **B7** | Go Notification Gateway: Dockerfile + unit tests + GitLab CI | S3-T14, S3-T16, S3-T18 | B6 |
| **B8** | Retry & DLQ: @RetryableTopic + DLT consumer с метриками | S3-T07, S3-T08 | Sprint 2 Kafka consumer |
| **B9** | Retry & DLQ: Integration test (failure → retry → DLT) | S3-T09 | B8 |
| **B10** | Tech Debt: Cooperative Sticky Assignor для consumer groups | S3-T17 | — |

---

## Зависимости между блоками

```
Saga ветка:
B1 (Payment Step) ──→ B2 (Payout Step) ──→ B3 (Compensation) ──→ B4 (Saga Tests)

Go Notification ветка:
B5 (Go Skeleton + Consumer) ──→ B6 (Adapters + Metrics) ──→ B7 (Docker + Tests + CI)

Retry ветка:
B8 (RetryableTopic + DLT) ──→ B9 (Retry Tests)

B10 (Cooperative Sticky Assignor) — независим, может делаться параллельно
```

Три параллельные ветки:
- **Saga ветка:** B1 → B2 → B3 → B4
- **Go Notification ветка:** B5 → B6 → B7
- **Retry ветка:** B8 → B9

Можно чередовать блоки из разных веток. Рекомендуемый порядок: начать Saga (B1–B2) параллельно с Go skeleton (B5), потом Saga compensation (B3) + Go delivery (B6), потом Retry (B8), потом тесты (B4, B7, B9).

---

## Детали каждого блока

### Block 1 — Saga: Payment Step

**Сервисы:** `services/transfer-service/`, новый `services/mock-payment-service/` (или внутренний модуль)

**Контекст:** В Sprint 2 Transfer Service создаёт перевод и записывает событие в outbox. Outbox Service публикует его в Kafka. Сейчас нужно замкнуть первый шаг Saga — инициировать платёж и получить ответ.

**Что делать:**

*Transfer Service — публикация команды:*
- При создании перевода (статус CREATED) — записывать в outbox событие `transfer.payment.requested` (топик `transfers.payment.requested`)
- Событие содержит: transfer_id, sender_id, amount, currency, payment_method, idempotency_key
- Transfer status transition: CREATED → PAYMENT_PENDING (после записи outbox)

*Mock Payment Service:*
- Новый Spring Boot модуль (минимальный: application.yml + один Kafka consumer)
- `@KafkaListener` на топик `transfers.payment.requested`
- Логика: десериализация → имитация обработки (задержка 100-500ms через Thread.sleep) → публикация результата
- Happy path: публикует `payments.payment.captured` в Kafka (key=transfer_id) с payment_id, captured_amount, captured_at
- Failure path: по определённому условию (например, amount > 10000 или специальный test flag) публикует `payments.payment.failed` с reason

**Почему Mock, а не реальный Payment Service:** Payment Service принадлежит Payments-команде (смежная команда). Мы имитируем их контракт, чтобы не зависеть от их реализации. На собеседовании: «Payments-команда владела Payment Service. Мы согласовали Kafka-контракт (события payment.captured/failed) и реализовали mock для локальной разработки и тестирования. Контракт зафиксирован в Avro-схеме.»

**Результат:** При создании перевода → outbox → Kafka → Mock Payment → payment.captured/failed → появляется в Kafka.

---

### Block 2 — Saga: Payout Step

**Сервисы:** `services/transfer-service/`, новый `services/mock-payout-service/`

**Контекст:** После успешного payment.captured нужно инициировать выплату получателю и обработать результат.

**Что делать:**

*Transfer Service — consumer для payment events (расширение существующего из Sprint 2):*
- При получении `payments.payment.captured` → update transfer status: PAYMENT_PENDING → PAYMENT_CAPTURED
- Записать в outbox событие `transfers.payout.requested` (инициация выплаты)
- Событие содержит: transfer_id, recipient_details, receive_amount, receive_currency, delivery_method, payout_partner

*Mock Payout Service:*
- Аналогично Mock Payment: минимальный Spring Boot, Kafka consumer
- `@KafkaListener` на топик `transfers.payout.requested`
- Happy path: публикует `payouts.payout.completed` (payout_id, completed_at, reference_number)
- Failure path: `payouts.payout.failed` (reason: INVALID_ACCOUNT, PARTNER_UNAVAILABLE, LIMIT_EXCEEDED)

*Transfer Service — consumer для payout events:*
- Новый `@KafkaListener` для `payouts.payout.completed` и `payouts.payout.failed`
- При `payout.completed` → update status: PAYMENT_CAPTURED → COMPLETED
- Записать в outbox `transfers.transfer.status_changed` (для Notification Service)

**Результат:** Полный happy path: создание → payment.captured → payout.completed → COMPLETED. Событие transfer.status_changed в Kafka на каждом шаге.

---

### Block 3 — Saga: Compensation

**Сервис:** `services/transfer-service/`

**Контекст:** Saga без компенсаций — не Saga. Нужно обработать failure paths и запустить откат.

**Что делать:**

*Payment failed — прямой failure:*
- При получении `payments.payment.failed` → update transfer status: PAYMENT_PENDING → FAILED
- Записать в outbox `transfers.transfer.status_changed` (status=FAILED, reason=payment_declined)
- Никакой компенсации не нужно — деньги не были списаны

*Payout failed — компенсация через refund:*
- При получении `payouts.payout.failed` → update transfer status: PAYMENT_CAPTURED → PAYOUT_FAILED
- Записать в outbox `transfers.payment.refund.requested` (компенсирующая команда)
- Событие содержит: transfer_id, payment_id, refund_amount, reason

*Refund completed:*
- Расширить consumer payment events: обработка `payments.payment.refunded`
- При получении → update status: PAYOUT_FAILED → REFUNDED
- Записать в outbox `transfers.transfer.status_changed` (status=REFUNDED)

*Обновление Mock Payment Service:*
- Добавить consumer для `transfers.payment.refund.requested`
- Публикует `payments.payment.refunded` (refund_id, refunded_at, refunded_amount)

*State machine — защита от невалидных переходов:*
- Убедиться, что sealed class TransferStatus и функция `transitionTo()` корректно обрабатывают все новые переходы
- Невалидные переходы (например, COMPLETED → FAILED) должны бросать `IllegalStateTransitionException`

**Результат:** Два failure path работают: payment.failed → FAILED; payout.failed → refund.requested → payment.refunded → REFUNDED.

---

### Block 4 — Saga: Integration Tests

**Сервисы:** `services/transfer-service/`

**Контекст:** Saga — самый сложный flow в системе. Без интеграционных тестов невозможно быть уверенным, что все переходы корректны.

**Что делать:**

*Happy path test:*
- Testcontainers: PostgreSQL + Kafka + Redis
- POST /api/v1/transfers → verify status=CREATED
- Publish mock `payments.payment.captured` в Kafka → poll DB до status=PAYMENT_CAPTURED
- Publish mock `payouts.payout.completed` в Kafka → poll DB до status=COMPLETED
- Verify: все промежуточные events в outbox помечены SENT
- Verify: transfer.status_changed events опубликованы для каждого перехода

*Payment failure test:*
- POST /api/v1/transfers → status=CREATED
- Publish `payments.payment.failed` → poll DB до status=FAILED
- Verify: нет события payout.requested в Kafka (saga прерывается)

*Payout failure + refund test:*
- POST /api/v1/transfers → CREATED → publish payment.captured → PAYMENT_CAPTURED
- Publish `payouts.payout.failed` → poll DB до status=PAYOUT_FAILED
- Verify: событие `transfers.payment.refund.requested` в Kafka
- Publish `payments.payment.refunded` → poll DB до status=REFUNDED

*Idempotency test:*
- Publish тот же payment.captured event дважды → verify transfer обновился только один раз (processed_events из Sprint 2)

**Результат:** 4+ интеграционных теста покрывают полный Saga lifecycle. Это ключевой safety net для всех будущих изменений в saga-логике.

---

### Block 5 — Go Notification Gateway: Skeleton + Kafka Consumer

**Сервис:** `services/notification-gateway/` (новый Go-сервис)

**Контекст:** Первый сервис на Go в проекте. Notification Gateway получает из Kafka команды на отправку нотификаций и доставляет их через внешних провайдеров (FCM, Twilio). На этом этапе — минимальный каркас и Kafka consumer.

**Что делать:**

*Project setup:*
- `go mod init github.com/transferhub/notification-gateway`
- Структура директорий:
  ```
  notification-gateway/
  ├── cmd/
  │   └── main.go                  # entrypoint
  ├── internal/
  │   ├── config/
  │   │   └── config.go            # env vars, Kafka brokers, topic names
  │   ├── consumer/
  │   │   └── consumer.go          # Kafka consumer loop
  │   ├── handler/
  │   │   └── handler.go           # бизнес-логика обработки event
  │   └── sender/
  │       ├── sender.go            # interface Sender
  │       ├── push.go              # FCM mock
  │       └── sms.go               # Twilio mock
  ├── go.mod
  ├── go.sum
  └── Makefile
  ```
- Зависимости: `github.com/segmentio/kafka-go` (или `github.com/confluentinc/confluent-kafka-go`), `github.com/rs/zerolog` для structured logging

*Kafka consumer:*
- Consumer group: `notification-delivery-consumer`
- Topic: `notification.delivery` (Transfer Service публикует через outbox после каждого status change)
- Десериализация JSON: transfer_id, sender_id, event_type, notification_text, channels (push/sms/email)
- Consumer loop: read batch → для каждого message → вызов handler.Handle(ctx, event) → commit offset
- Manual offset commit (after successful processing) — at-least-once semantics

*Health endpoint:*
- HTTP server на :8081 — `GET /healthz` → 200 (для Kubernetes liveness probe)
- `GET /readyz` → 200 если Kafka consumer connected (для readiness probe)

**Результат:** Go-сервис стартует, подключается к Kafka, читает messages из `notification.delivery`, логирует в structured JSON.

---

### Block 6 — Go Notification Gateway: Delivery + Metrics + Graceful Shutdown

**Сервис:** `services/notification-gateway/`

**Контекст:** Consumer работает (B5), теперь нужна логика доставки, метрики и корректное завершение.

**Что делать:**

*Delivery adapters (interface + mock implementations):*
- Interface `Sender`:
  ```go
  type Sender interface {
      Send(ctx context.Context, notification Notification) error
      Channel() string  // "push", "sms", "email"
  }
  ```
- `PushSender`: mock FCM — логирует "Sending push to device_token: ... message: ...", возвращает nil (success) или error (для тестирования retry)
- `SMSSender`: mock Twilio — аналогично, логирует "Sending SMS to phone: ... message: ..."
- `SenderRouter`: получает event → определяет каналы (event.Channels: ["push", "sms"]) → вызывает соответствующие Sender'ы
- Если один канал failed, другие всё равно отправляются (partial success допустим)

*Graceful shutdown:*
- `signal.NotifyContext` для SIGTERM/SIGINT
- При получении сигнала: stop consuming new messages → finish processing current batch → commit offsets → close Kafka consumer → close HTTP server → exit
- Timeout на shutdown: 30 секунд (потом force exit)
- Логирование: "Received shutdown signal, draining..." → "Shutdown complete"

*Prometheus metrics:*
- `prometheus/client_golang` — HTTP handler на `/metrics` (port 8082 или тот же 8081)
- Метрики:
  - `notification_delivery_total` (counter): labels={channel, status} — сколько нотификаций отправлено, по каналу и результату (success/failure)
  - `notification_delivery_duration_seconds` (histogram): labels={channel} — latency отправки
  - `notification_consumer_lag` (gauge): текущий lag consumer'а (если библиотека предоставляет)
  - `notification_consumer_messages_processed_total` (counter): сколько Kafka messages обработано

**Результат:** Notification Gateway обрабатывает events, маршрутизирует по каналам, экспортирует Prometheus-метрики, корректно завершается при SIGTERM.

---

### Block 7 — Go Notification Gateway: Docker + Tests + CI

**Сервис:** `services/notification-gateway/`

**Контекст:** Сервис работает локально (B5, B6), теперь нужно его контейнеризовать, покрыть тестами и добавить в CI.

**Что делать:**

*Dockerfile (multi-stage, scratch):*
```dockerfile
# Stage 1: Build
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /notification-gateway ./cmd/main.go

# Stage 2: Runtime
FROM scratch
COPY --from=builder /notification-gateway /notification-gateway
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
USER 65534:65534
ENTRYPOINT ["/notification-gateway"]
```
- Итоговый образ: ~15MB (scratch + статический бинарник)
- ca-certificates для HTTPS-вызовов к внешним провайдерам
- Non-root user (nobody: 65534)

*Unit tests (Go table-driven):*
- `handler_test.go`: table-driven tests для Handler.Handle()
  - test: valid push event → sender called with correct args
  - test: valid multi-channel event → both senders called
  - test: unknown channel → logged warning, no error
  - test: sender returns error → error propagated, other channels still attempted
- `router_test.go`: SenderRouter routing logic
- `go test -v -cover ./...` → target coverage > 70%

*GitLab CI:*
- Новый job в `.gitlab-ci.yml` для Go-сервиса:
  ```yaml
  notification-gateway:lint:
    image: golangci/golangci-lint:v1.56
    script:
      - cd services/notification-gateway
      - golangci-lint run ./...

  notification-gateway:test:
    image: golang:1.22
    script:
      - cd services/notification-gateway
      - go test -v -cover -coverprofile=coverage.out ./...
      - go tool cover -func=coverage.out

  notification-gateway:build:
    script:
      - docker build -t $CI_REGISTRY_IMAGE/notification-gateway:$CI_COMMIT_SHA services/notification-gateway/
      - docker push $CI_REGISTRY_IMAGE/notification-gateway:$CI_COMMIT_SHA
  ```

*Docker Compose:*
- Добавить `notification-gateway` в docker-compose.yml
- Зависимости: Kafka
- Environment: KAFKA_BROKERS, NOTIFICATION_TOPIC, LOG_LEVEL, METRICS_PORT

**Результат:** `docker compose up` поднимает Notification Gateway. CI собирает, линтит и тестирует Go-сервис. Docker-образ ~15MB.

---

### Block 8 — Retry & DLQ: @RetryableTopic + DLT Consumer

**Сервис:** `services/transfer-service/` (или отдельный Notification Service модуль)

**Контекст:** В Sprint 2 consumer обрабатывает events. Но если обработка фейлится (провайдер нотификаций недоступен, сетевая ошибка) — сообщение теряется или блокирует consumer. Нужен non-blocking retry с DLQ.

**Что делать:**

*@RetryableTopic конфигурация:*
- Spring Kafka @RetryableTopic annotation на consumer'е `notification.commands`:
  ```kotlin
  @RetryableTopic(
      attempts = "4",        // 1 original + 3 retries
      backoff = @Backoff(
          delay = 30_000,    // 30 секунд первый retry
          multiplier = 10.0, // 30s → 5min → 50min
          maxDelay = 3600_000 // max 1 час
      ),
      topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
      dltStrategy = DltStrategy.FAIL_ON_ERROR
  )
  ```
- Spring Kafka автоматически создаёт retry-топики: `notification.commands-retry-0`, `notification.commands-retry-1`, `notification.commands-retry-2` и DLT: `notification.commands-dlt`
- Каждый retry-топик обрабатывается с задержкой (backoff): сообщение перепубликуется в следующий retry-топик при ошибке

*DLT consumer:*
- `@DltHandler` метод: обработка сообщений, попавших в Dead Letter Topic
- Логирование: structured log с level=ERROR, event_id, transfer_id, exception, retry_count
- Метрика: `kafka_dlt_messages_total` (counter, labels: topic, exception_class) через Micrometer
- На этом этапе — только логирование и метрика. Ручной replay из DLT — в будущем (Sprint 4+)

*Конфигурация для тестируемости:*
- Возможность имитировать ошибку через специальный header или field в event (например, `simulate_failure=true`) — для integration tests
- Чёткое разделение: retriable exceptions (TransientException — сетевые ошибки, timeout) vs non-retriable (ValidationException — невалидные данные, сразу в DLT)

**Результат:** При ошибке обработки — автоматический retry с backoff. После исчерпания попыток — в DLT с метрикой и логом. Consumer не блокируется.

---

### Block 9 — Retry & DLQ: Integration Test

**Сервис:** `services/transfer-service/`

**Контекст:** Retry-механизм сложный (несколько топиков, backoff, DLT) — без теста легко сломать.

**Что делать:**

*Integration test с Testcontainers (Kafka):*
- Test 1: Transient failure → retry → success
  - Publish event в `notification.commands`
  - Consumer бросает TransientException при первой попытке
  - Verify: event появился в retry-топике
  - При второй попытке — success
  - Verify: event НЕ в DLT

- Test 2: Permanent failure → все retry → DLT
  - Publish event с `simulate_failure=true`
  - Consumer бросает exception на каждую попытку
  - Verify: event прошёл через все retry-топики
  - Verify: event в DLT
  - Verify: метрика `kafka_dlt_messages_total` инкрементирована

- Test 3: Non-retriable exception → сразу в DLT
  - Publish event с невалидными данными
  - Consumer бросает ValidationException (non-retriable)
  - Verify: event сразу в DLT, без прохождения retry-топиков

*Примечание по таймингам:*
- В тестах backoff задержки должны быть минимальными (override через test-конфигурацию: delay=100ms, multiplier=1) — иначе тест будет выполняться минуты
- `@TestPropertySource` или test-specific application-test.yml для переопределения backoff

**Результат:** 3 интеграционных теста подтверждают корректность retry/DLQ pipeline.

---

### Block 10 — Tech Debt: Cooperative Sticky Assignor

**Все сервисы с Kafka consumers**

**Контекст:** По умолчанию Spring Kafka использует RangeAssignor для распределения партиций между consumer'ами в group. При ребалансировке (добавление/удаление consumer'а) — все партиции отзываются и перераспределяются (eager rebalancing). Это вызывает stop-the-world для всей consumer group на время ребалансировки. CooperativeStickyAssignor решает эту проблему.

**Что делать:**

*Конфигурация consumer'ов:*
- В application.yml для каждого сервиса с Kafka consumer:
  ```yaml
  spring:
    kafka:
      consumer:
        properties:
          partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
  ```
- CooperativeStickyAssignor: при ребалансировке перемещаются только партиции, которые нужно переместить. Остальные consumer'ы продолжают обработку без прерывания (incremental cooperative rebalancing)
- Применить к: Transfer Service (payment events consumer, payout events consumer), Notification Gateway (если Go-клиент поддерживает — проверить)

*Для Go Notification Gateway:*
- Проверить, поддерживает ли выбранная Go Kafka-библиотека CooperativeStickyAssignor
- `segmentio/kafka-go`: по умолчанию использует simple balancer, проверить опции
- Если не поддерживается — задокументировать как known limitation

**Результат:** Ребалансировка consumer groups проходит без полной остановки обработки. На собеседовании: «Мы переключили consumer groups на CooperativeStickyAssignor, чтобы при масштабировании (добавлении нового Pod'а) обработка не прерывалась полностью — только партиции, которые нужно перераспределить, перемещаются, остальные продолжают работать.»

---

## Зависимости между блоками (детально)

```
                    ┌─────────────────────────────────────────────────┐
                    │              SAGA ВЕТКА                          │
                    │                                                 │
                    │  B1 (Payment Step)                              │
                    │    ↓                                            │
                    │  B2 (Payout Step)                               │
                    │    ↓                                            │
                    │  B3 (Compensation)                              │
                    │    ↓                                            │
                    │  B4 (Saga Integration Tests)                    │
                    └─────────────────────────────────────────────────┘

                    ┌─────────────────────────────────────────────────┐
                    │         GO NOTIFICATION ВЕТКА                    │
                    │                                                 │
                    │  B5 (Go Skeleton + Consumer)                    │
                    │    ↓                                            │
                    │  B6 (Adapters + Metrics + Shutdown)             │
                    │    ↓                                            │
                    │  B7 (Docker + Tests + CI)                       │
                    └─────────────────────────────────────────────────┘

                    ┌─────────────────────────────────────────────────┐
                    │            RETRY ВЕТКА                           │
                    │                                                 │
                    │  B8 (@RetryableTopic + DLT Consumer)            │
                    │    ↓                                            │
                    │  B9 (Retry Integration Tests)                   │
                    └─────────────────────────────────────────────────┘

                    B10 (Cooperative Sticky Assignor) — независим
```

## Рекомендуемый порядок работы

Три ветки можно чередовать. Рекомендуемая последовательность для баланса между прогрессом и переключением контекста:

1. **B1** — Saga Payment Step (Kotlin, знакомый стек, продолжение Sprint 2)
2. **B5** — Go Notification Skeleton (переключение на Go, пока свежий взгляд)
3. **B2** — Saga Payout Step (вернулись к Kotlin, закрепляем saga flow)
4. **B6** — Go Delivery + Metrics (продолжаем Go, пока в контексте)
5. **B3** — Saga Compensation (failure paths — самая important часть Saga)
6. **B8** — Retry & DLQ (Spring Kafka RetryableTopic — новый паттерн)
7. **B7** — Go Docker + Tests + CI (завершаем Go-сервис)
8. **B4** — Saga Integration Tests (полное покрытие тестами)
9. **B9** — Retry Integration Tests (подтверждение retry pipeline)
10. **B10** — Cooperative Sticky Assignor (tech debt, quick win)

---

## Новые Kafka-топики в Sprint 3

| Топик | Producer | Consumer | Назначение |
|-------|----------|----------|-----------|
| `transfers.payment.requested` | Transfer Service (outbox) | Mock Payment Service | Команда на списание |
| `transfers.payout.requested` | Transfer Service (outbox) | Mock Payout Service | Команда на выплату |
| `transfers.payment.refund.requested` | Transfer Service (outbox) | Mock Payment Service | Компенсация: запрос возврата |
| `payments.payment.captured` | Mock Payment Service | Transfer Service | Платёж успешен |
| `payments.payment.failed` | Mock Payment Service | Transfer Service | Платёж не прошёл |
| `payments.payment.refunded` | Mock Payment Service | Transfer Service | Возврат выполнен |
| `payouts.payout.completed` | Mock Payout Service | Transfer Service | Выплата завершена |
| `payouts.payout.failed` | Mock Payout Service | Transfer Service | Выплата не удалась |
| `notification.delivery` | Transfer Service (outbox) | Notification Gateway (Go) | Команда на доставку уведомления |
| `notification.commands-retry-*` | Spring Kafka | Spring Kafka | Auto-retry топики |
| `notification.commands-dlt` | Spring Kafka | DLT Handler | Dead Letter Topic |

---

## Итого Sprint 3

| Метрика | Значение |
|---------|----------|
| Блоков | 10 |
| Новые сервисы | 3 (Mock Payment, Mock Payout, Notification Gateway) |
| Новый язык | Go (первый polyglot-сервис в проекте) |
| Технологии | Choreography-based Saga, @RetryableTopic, DLQ, Go Kafka consumer, Prometheus Go client |
| Паттерны | Saga Pattern (с компенсацией), Non-blocking Retry, Dead Letter Queue, Graceful Shutdown |
| Тесты | Integration: Saga happy+failure paths, Retry→DLT. Unit: Go table-driven tests |
| Kafka-топиков новых | ~11 (включая retry + DLT) |
| Docker-образов новых | 3 (mock-payment, mock-payout: JVM ~150MB; notification-gateway: Go ~15MB) |
