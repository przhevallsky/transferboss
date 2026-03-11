# TransferBoss: Architecture & Flow Guide

> Полное описание архитектуры, потоков данных и используемых паттернов.
> Актуально после Sprint 3.

---

## 1. System Overview

TransferBoss — домен международных денежных переводов платформы TransferHub.
Monorepo с 6 сервисами:

| Сервис | Язык / Фреймворк | Порт(ы) | База данных | Роль |
|---|---|---|---|---|
| **transfer-service** | Kotlin / Spring Boot 3 | 8080 | PostgreSQL + Redis | Основной сервис: API, бизнес-логика, saga orchestrator |
| **outbox-service** | Kotlin / Spring Boot 3 | 8081 | PostgreSQL | Polling outbox таблицы → публикация в Kafka |
| **pricing-service** | Kotlin / Ktor + gRPC | 8082, 9090 | MongoDB + Redis | Котировки и валидация курсов |
| **mock-payment-service** | Kotlin / Spring Boot | 8083 | — | Симуляция платежного провайдера |
| **mock-payout-service** | Kotlin / Spring Boot | 8084 | — | Симуляция провайдера выплат |
| **notification-gateway** | Go 1.23 | 8085, 8086 | — | Доставка уведомлений (push, SMS) |

**Инфраструктура:**
- PostgreSQL 16 — основная БД
- Redis 7 — кеширование (Cache-Aside)
- Apache Kafka 7.6 (KRaft, без Zookeeper) — event streaming
- HashiCorp Consul 1.18 — distributed locking
- Docker Compose — оркестрация всех сервисов

---

## 2. Architecture Diagram

```
                          ┌─────────────────────┐
                          │   Client (REST API)  │
                          └──────────┬───────────┘
                                     │ HTTP
                          ┌──────────▼───────────┐
                          │   Transfer Service    │
                          │    (Kotlin/Spring)    │
                          │      port: 8080       │
                          └─┬──────┬──────┬───┬──┘
                            │      │      │   │
              ┌─────────────┤      │      │   └──────────────────┐
              │  gRPC        │      │ Redis│                     │ Consul
              ▼              │      ▼      ▼                     ▼
   ┌──────────────────┐     │  ┌───────┐ ┌──────┐    ┌──────────────────┐
   │ Pricing Service  │     │  │ Redis │ │Postgr│    │      Consul      │
   │  (Ktor + gRPC)   │     │  │ Cache │ │  SQL │    │ Distributed Lock │
   │  port: 9090      │     │  └───────┘ └──┬───┘    └──────────────────┘
   └──────────────────┘     │               │
                            │     ┌─────────▼─────────┐
                            │     │   Outbox Service   │
                            │     │     port: 8081     │
                            │     └─────────┬─────────┘
                            │               │ polls outbox table
                            │               │ publishes to Kafka
                            │     ┌─────────▼─────────┐
                            │     │      Kafka         │
                            │     │   (KRaft mode)     │
                            └─────┤    port: 9092      ├─────────────────┐
                           consume│                    │publish           │
                            ┌─────▼──────┐   ┌────────▼────────┐  ┌─────▼──────────────┐
                            │ Mock       │   │ Mock Payout     │  │ Notification       │
                            │ Payment    │   │ Service         │  │ Gateway (Go)       │
                            │ Service    │   │ port: 8084      │  │ ports: 8085, 8086  │
                            │ port: 8083 │   └─────────────────┘  └────────────────────┘
                            └────────────┘
```

---

## 3. Database Schema

5 таблиц создаются через Flyway миграции (`resources/db/migration/`):

### transfers (V001)
Основная таблица переводов. Ключевые колонки:
- `id` UUID PK, `idempotency_key` UNIQUE — защита от дублей
- `send_amount`, `receive_amount`, `exchange_rate`, `fee_amount` — финансовые данные (NUMERIC)
- `source_country`, `dest_country` — коридор перевода
- `status` VARCHAR(30) + CHECK constraint — state machine
- `payment_id`, `payout_id` — saga tracking (заполняются при получении событий)
- `version` — optimistic locking
- Индексы: `(sender_id, created_at DESC)` для пагинации, partial индексы по статусу

### recipients (V002)
- `delivery_details` JSONB — гибкая схема под разные методы доставки
- `is_active` — soft delete

### outbox_events (V003)
- `entity_id`, `event_type`, `payload` JSONB, `target_topic`
- `status`: PENDING → SENT | FAILED
- Индекс `idx_outbox_pending` для polling

### idempotency_keys (V004)
- Кеширование HTTP-ответов, TTL 24 часа

### consumed_events (V005)
- `event_id` PK — дедупликация Kafka-сообщений

---

## 4. Domain Model: Transfer Entity

Файл: `domain/model/Transfer.kt`

```kotlin
@Entity
@Table(name = "transfers")
class Transfer(
    @Id val id: UUID = UUID.randomUUID(),
    val idempotencyKey: UUID,
    val senderId: UUID,
    val quoteId: UUID,

    // Финансовые данные (immutable)
    val sendAmount: BigDecimal,
    val sendCurrency: String,
    val receiveAmount: BigDecimal,
    val receiveCurrency: String,
    val exchangeRate: BigDecimal,
    val feeAmount: BigDecimal,

    // Маршрут
    val sourceCountry: String,
    val destCountry: String,
    val deliveryMethod: DeliveryMethod,
    val recipientId: UUID,

    // Состояние (mutable)
    @Convert(converter = TransferStatusConverter::class)
    var status: TransferStatus = TransferStatus.Created,
    var statusReason: String? = null,

    // Saga tracking
    var paymentId: UUID? = null,
    var payoutId: UUID? = null,

    // Optimistic locking
    @Version var version: Int = 0,

    // Аудит
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var completedAt: Instant? = null
) {
    fun transitionTo(newStatus: TransferStatus, reason: String? = null) {
        check(status.canTransitionTo(newStatus)) {
            "Invalid status transition: ${status.value} -> ${newStatus.value}"
        }
        status = newStatus
        statusReason = reason
        updatedAt = Instant.now()
        if (newStatus.isTerminal()) completedAt = Instant.now()
    }
}
```

---

## 5. State Machine: TransferStatus

Файл: `domain/model/TransferStatus.kt`

```
                                    ┌──────────────────┐
                                    │     CREATED      │
                                    └───────┬──────────┘
                                            │
                               ┌────────────▼────────────┐
                               │   COMPLIANCE_CHECK      │
                               └─────┬───────────┬───────┘
                                     │           │
                          ┌──────────▼──┐   ┌────▼──────────────┐
                          │COMPLIANCE   │   │ COMPLIANCE        │
                          │_HOLD        │   │ _REJECTED         │ ← terminal
                          └──────┬──────┘   └───────────────────┘
                                 │
                     ┌───────────▼────────────┐
                     │    PAYMENT_PENDING      │
                     └─────┬────────────┬──────┘
                           │            │
               ┌───────────▼──┐   ┌─────▼──────────┐
               │  PAYMENT     │   │ PAYMENT_FAILED  │ ← terminal
               │  _CAPTURED   │   └─────────────────┘
               └───────┬──────┘
                       │
              ┌────────▼─────────┐
              │  PAYOUT_PENDING  │
              └──┬───────────┬───┘
                 │           │
     ┌───────────▼──┐  ┌────▼──────┐
     │  DELIVERING  │  │  FAILED   │
     └───────┬──────┘  └─────┬─────┘
             │               │
    ┌────────▼──────┐  ┌─────▼──────────┐
    │  COMPLETED    │  │ REFUND_PENDING  │
    │  (terminal)   │  └──────┬──────────┘
    └───────────────┘         │
                      ┌───────▼──────┐
                      │  REFUNDED    │ ← terminal
                      └──────────────┘
```

Реализация — Kotlin sealed class с exhaustive `when`:

```kotlin
sealed class TransferStatus(val value: String) {
    data object Created : TransferStatus("CREATED")
    data object PaymentPending : TransferStatus("PAYMENT_PENDING")
    data object PaymentCaptured : TransferStatus("PAYMENT_CAPTURED")
    data object PayoutPending : TransferStatus("PAYOUT_PENDING")
    data object Completed : TransferStatus("COMPLETED")
    // ... и другие

    fun allowedTransitions(): Set<TransferStatus> = when (this) {
        Created -> setOf(ComplianceCheck, PaymentPending, Cancelled)
        PaymentPending -> setOf(PaymentCaptured, PaymentFailed)
        PaymentCaptured -> setOf(PayoutPending)
        PayoutPending -> setOf(Delivering, Completed, Failed)
        Failed -> setOf(RefundPending)
        RefundPending -> setOf(Refunded)
        Completed -> emptySet() // terminal
        // ...
    }

    fun isTerminal(): Boolean = allowedTransitions().isEmpty()
}
```

---

## 6. REST API: Entry Point

Файл: `api/controller/TransferController.kt`

### POST /api/v1/transfers — Создание перевода

```kotlin
@PostMapping
fun createTransfer(
    @Valid @RequestBody request: CreateTransferRequest,
    @RequestHeader("X-Idempotency-Key") idempotencyKey: UUID,
    @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?
): ResponseEntity<TransferResponse> {
    val senderId = senderIdHeader ?: UUID.fromString("00000000-0000-0000-0000-000000000001")
    val command = request.toCommand(senderId = senderId, idempotencyKey = idempotencyKey)
    val (result, isNew) = transferService.createTransfer(command)
    val response = result.transfer.toResponse(result.recipient)

    return if (isNew) {
        ResponseEntity.created(URI.create("/api/v1/transfers/${result.transfer.id}")).body(response)
    } else {
        ResponseEntity.ok(response) // idempotency hit → 200
    }
}
```

### GET /api/v1/transfers/{id} — с Cache-Aside

```kotlin
@GetMapping("/{id}")
fun getTransfer(@PathVariable id: UUID): ResponseEntity<TransferResponse> {
    val cached = transferCacheService.getCached(id)  // Redis check
    if (cached != null) return ResponseEntity.ok(cached)

    val result = transferService.getTransfer(id)
    val response = result.transfer.toResponse(result.recipient)
    transferCacheService.put(id, response)  // cache for 30s
    return ResponseEntity.ok(response)
}
```

### GET /api/v1/transfers — Cursor-based пагинация

Параметры: `cursor` (opaque Base64), `limit` (1-100, default 20)

---

## 7. Service Layer: createTransfer() — Полный Flow

Файл: `service/TransferService.kt`

Это ключевой метод. Вот что происходит шаг за шагом:

```
POST /api/v1/transfers
    │
    ▼
TransferService.createTransfer(command)
    │
    ├─ 1. DISTRIBUTED LOCK (Consul)
    │     key: "sender/{senderId}/create"
    │
    ├─ 2. IDEMPOTENCY CHECK
    │     transferRepository.findByIdempotencyKey(key)
    │     → hit? return existing, isNew=false
    │
    ├─ 3. BUSINESS VALIDATION
    │     ├─ Corridor supported? (US_PH, US_MX, GB_IN, US_IN)
    │     ├─ Delivery method allowed for corridor?
    │     └─ Amount >= minimum? ($10 US, £5 GB)
    │
    ├─ 4. RECIPIENT LOOKUP
    │     ├─ Exists?
    │     └─ Belongs to sender? (security)
    │
    ├─ 5. gRPC: VALIDATE QUOTE (Pricing Service)
    │     ├─ Circuit breaker (Resilience4j)
    │     ├─ 3-second deadline
    │     └─ Currency consistency check
    │
    ├─ 6. CREATE TRANSFER (status = CREATED)
    │
    ├─ 7. CREATE OUTBOX EVENT (TRANSFER_CREATED)
    │     ↑ Same @Transactional — atomic!
    │
    ├─ 8. TRANSITION → PAYMENT_PENDING
    │
    ├─ 9. CREATE OUTBOX EVENT (PAYMENT_REQUESTED)
    │     targetTopic: "transfers.payment.requested"
    │
    └─ 10. SAVE ALL — single DB transaction commit
```

Ключевой код:

```kotlin
@Transactional
fun createTransfer(command: CreateTransferCommand): Pair<TransferWithRecipient, Boolean> {
    val lockKey = "sender/${command.senderId}/create"

    return distributedLockService.executeWithLock(lockKey) {
        // 1. Idempotency
        val existing = transferRepository.findByIdempotencyKey(command.idempotencyKey)
        if (existing != null) return@executeWithLock Pair(/*...*/, false)

        // 2. Validate
        validateTransfer(command)
        val recipient = recipientRepository.findRecipientById(command.recipientId)
            ?: throw RecipientNotFoundException(command.recipientId)

        // 3. gRPC to Pricing
        val quoteData = pricingClient.validateQuote(command.quoteId.toString())

        // 4. Create entity + outbox events (ОДНА транзакция!)
        val transfer = Transfer(/* ... from quoteData ... */)
        val outboxEvent = OutboxEvent(
            entityId = transfer.id,
            eventType = OutboxEventType.TRANSFER_CREATED,
            payload = buildTransferCreatedPayload(transfer, recipient),
            status = OutboxEventStatus.PENDING
        )
        transferRepository.save(transfer)
        outboxEventRepository.save(outboxEvent)

        // 5. Saga: request payment
        outboxEventRepository.save(OutboxEvent(
            entityId = transfer.id,
            eventType = OutboxEventType.PAYMENT_REQUESTED,
            targetTopic = "transfers.payment.requested",
            status = OutboxEventStatus.PENDING
        ))
        transfer.transitionTo(TransferStatus.PaymentPending)
        transferRepository.save(transfer)

        Pair(TransferWithRecipient(transfer, recipient), true)
    }
}
```

---

## 8. gRPC: Pricing Service Integration

Файл: `client/PricingClient.kt`

Transfer Service вызывает Pricing Service через gRPC для валидации котировки:

```kotlin
@Component
class PricingClient(pricingChannel: ManagedChannel) {
    private val stub = PricingServiceGrpc.newBlockingStub(pricingChannel)

    // Circuit Breaker: 50% failure threshold, 30s open, window=10 calls
    private val circuitBreaker = CircuitBreaker.of("pricing-service",
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .build()
    )

    fun validateQuote(quoteId: String): QuoteData {
        return circuitBreaker.executeSupplier {
            val response = stub
                .withDeadlineAfter(3, TimeUnit.SECONDS) // 3s timeout
                .validateQuote(ValidateQuoteRequest.newBuilder()
                    .setQuoteId(quoteId).build())

            if (!response.isValid) throw QuoteExpiredException(quoteId)

            QuoteData(/* map from proto response */)
        }
        // StatusRuntimeException → QuoteExpiredException или PricingUnavailableException
        // CallNotPermittedException → PricingUnavailableException (circuit open)
    }
}
```

Proto-определение (`pricing/v1/pricing_service.proto`):
```protobuf
service PricingService {
  rpc GetQuote (GetQuoteRequest) returns (QuoteResponse);
  rpc ValidateQuote (ValidateQuoteRequest) returns (ValidateQuoteResponse);
}
```

---

## 9. Distributed Locking (Consul)

Файл: `lock/ConsulDistributedLockService.kt`

Защищает от concurrent создания/обновления:

```kotlin
@Service
@ConditionalOnProperty(name = ["consul.lock.enabled"], havingValue = "true")
class ConsulDistributedLockService(
    private val consulClient: ConsulClient,
    private val properties: ConsulLockProperties
) : DistributedLockService {

    override fun <T> executeWithLock(key: String, action: () -> T): T {
        val fullKey = "${properties.keyPrefix}/$key"  // e.g. "locks/transfer/sender/{id}/create"
        val sessionId = createSession()               // TTL = 15s
        try {
            acquireLock(fullKey, sessionId)            // retry с exponential backoff
            return action()
        } finally {
            releaseLock(fullKey, sessionId)            // best-effort, TTL auto-release
        }
    }
}
```

Используемые lock-ключи:
- `locks/transfer/sender/{senderId}/create` — при создании перевода
- `locks/transfer/transfer/{transferId}/status` — при смене статуса

---

## 10. Transactional Outbox Pattern

Ключевая гарантия: **Transfer + OutboxEvent сохраняются в ОДНОЙ транзакции**.

```
┌──────────────────────────────────────────────────────────────┐
│                    PostgreSQL Transaction                      │
│                                                                │
│  INSERT INTO transfers (...)        ← бизнес-данные           │
│  INSERT INTO outbox_events (...)    ← событие для Kafka       │
│                                                                │
│  COMMIT  →  оба записаны атомарно                             │
└──────────────────────────────────────────────────────────────┘
         │
         │  Outbox Service polls (отдельный процесс)
         │  SELECT * FROM outbox_events WHERE status = 'PENDING'
         ▼
┌─────────────────┐
│     Kafka       │  ← событие опубликовано
└─────────────────┘
         │
         │  UPDATE outbox_events SET status = 'SENT'
         ▼
```

OutboxEvent entity:
```kotlin
@Entity
@Table(name = "outbox_events")
class OutboxEvent(
    @Id val id: UUID = UUID.randomUUID(),
    val entityId: UUID,           // transfer_id (Kafka key для ordering)
    val entityType: String,       // "TRANSFER"
    val eventType: OutboxEventType, // TRANSFER_CREATED, PAYMENT_REQUESTED, etc.
    val payload: String,          // JSON
    var status: OutboxEventStatus,// PENDING → SENT | FAILED
    val targetTopic: String? = null,
    val createdAt: Instant = Instant.now()
)
```

---

## 11. Saga Choreography: Полные Flows

### Happy Path: Перевод от создания до завершения

```
Шаг 1: POST /api/v1/transfers
  └─ Transfer(CREATED) → Transfer(PAYMENT_PENDING)
  └─ OutboxEvent(PAYMENT_REQUESTED) → Kafka: transfers.payment.requested

Шаг 2: Mock Payment Service получает запрос
  └─ Обрабатывает платеж
  └─ Публикует в Kafka: payments.payment.captured

Шаг 3: PaymentEventConsumer получает событие
  └─ Transfer(PAYMENT_PENDING → PAYMENT_CAPTURED)
  └─ Transfer(PAYMENT_CAPTURED → PAYOUT_PENDING)
  └─ OutboxEvent(PAYOUT_REQUESTED) → Kafka: transfers.payout.requested

Шаг 4: Mock Payout Service получает запрос
  └─ Выполняет выплату получателю
  └─ Публикует в Kafka: payouts.payout.completed

Шаг 5: PayoutEventConsumer получает событие
  └─ Transfer(PAYOUT_PENDING → COMPLETED) ← terminal
  └─ Cache evict
```

### Compensation: Payout Failed → Refund

```
Шаг 4 (failure): Mock Payout Service
  └─ Выплата не удалась
  └─ Публикует: payouts.payout.failed

Шаг 5: PayoutEventConsumer
  └─ Transfer(PAYOUT_PENDING → FAILED)
  └─ Transfer(FAILED → REFUND_PENDING)
  └─ OutboxEvent(REFUND_REQUESTED) → Kafka: transfers.payment.refund.requested

Шаг 6: Mock Payment Service получает refund request
  └─ Выполняет возврат
  └─ Публикует: payments.payment.refunded

Шаг 7: PaymentEventConsumer
  └─ Transfer(REFUND_PENDING → REFUNDED) ← terminal
```

### Kafka Topics Map

```
Transfer Service PUBLISHES (via Outbox):        Transfer Service CONSUMES:
  transfers.payment.requested        ──→          payments.payment.captured
  transfers.payout.requested         ──→          payments.payment.failed
  transfers.payment.refund.requested ──→          payments.payment.refunded
  notification.delivery              ──→          payouts.payout.completed
                                                  payouts.payout.failed
```

---

## 12. Kafka Consumers: Retry & DLT

Файл: `consumer/PaymentEventConsumer.kt`, `consumer/PayoutEventConsumer.kt`

Оба consumer'а имеют идентичную структуру:

```kotlin
@RetryableTopic(
    attempts = "4",           // 1 original + 3 retry
    backoff = Backoff(
        delayExpression = "\${kafka.retry.delay:30000}",        // 30s
        multiplierExpression = "\${kafka.retry.multiplier:10.0}", // x10
        maxDelayExpression = "\${kafka.retry.max-delay:3600000}"  // 1h max
    ),
    topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
    dltStrategy = DltStrategy.FAIL_ON_ERROR,
    exclude = [NonRetriableConsumerException::class]  // не ретраим невалидные данные
)
@KafkaListener(
    topics = ["payments.payment.captured", "payments.payment.failed", "payments.payment.refunded"],
    groupId = "transfer-service"
)
fun consume(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
    // 1. Deserialize → NonRetriableConsumerException if fails
    val event = objectMapper.readValue(message, PaymentEvent::class.java)

    // 2. Map event type → target TransferStatus
    val newStatus = when (event.eventType) {
        "PAYMENT_CAPTURED" -> TransferStatus.PaymentCaptured
        "PAYMENT_FAILED"   -> TransferStatus.PaymentFailed
        "PAYMENT_REFUNDED" -> TransferStatus.Refunded
        else -> throw NonRetriableConsumerException("Unknown type")
    }

    // 3. Execute in transaction
    transactionTemplate.execute {
        // Deduplication check
        if (consumedEventRepository.existsByEventId(event.eventId)) return@execute false

        val transfer = transferRepository.findTransferById(transferId)
            ?: throw TransientConsumerException("Transfer not found") // → retry

        transfer.transitionTo(newStatus, event.reason)
        transfer.paymentId = UUID.fromString(event.paymentId)
        transferRepository.save(transfer)

        // If payment captured → request payout (next saga step)
        if (newStatus == TransferStatus.PaymentCaptured) {
            outboxEventRepository.save(OutboxEvent(
                eventType = OutboxEventType.PAYOUT_REQUESTED,
                targetTopic = "transfers.payout.requested",
                // ...
            ))
            transfer.transitionTo(TransferStatus.PayoutPending)
            transferRepository.save(transfer)
        }

        consumedEventRepository.save(ConsumedEvent(event.eventId, "transfer-service", topic))
        true
    }

    transferCacheService.evict(transferId) // invalidate cache
}

@DltHandler
fun handleDlt(message: String, ...) {
    dltCounter.increment()  // Prometheus metric
    log.error("Payment event sent to DLT: ...")
}
```

**Retry timeline:**
```
Attempt 1: payments.payment.captured           (immediate)
Attempt 2: payments.payment.captured-retry-0   (30s delay)
Attempt 3: payments.payment.captured-retry-1   (300s = 5min delay)
Attempt 4: payments.payment.captured-retry-2   (3000s → capped at 3600s = 1h)
  Fail:    payments.payment.captured-dlt       (dead letter)
```

**Exception types:**
- `TransientConsumerException` — ретраится (transfer not found = eventual consistency)
- `NonRetriableConsumerException` — сразу в DLT (bad JSON, unknown event type)

---

## 13. Cache-Aside Pattern (Redis)

Файл: `service/TransferCacheService.kt`

```kotlin
@Service
class TransferCacheService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    companion object {
        private const val KEY_PREFIX = "transfer:status:"
        private val CACHE_TTL = Duration.ofSeconds(30)
    }

    fun getCached(transferId: UUID): TransferResponse? {
        val json = redisTemplate.opsForValue().get("$KEY_PREFIX$transferId")
        return json?.let { objectMapper.readValue(it, TransferResponse::class.java) }
    }

    fun put(transferId: UUID, response: TransferResponse) {
        val json = objectMapper.writeValueAsString(response)
        redisTemplate.opsForValue().set("$KEY_PREFIX$transferId", json, CACHE_TTL)
    }

    fun evict(transferId: UUID) {
        redisTemplate.delete("$KEY_PREFIX$transferId")
    }
}
```

```
GET /api/v1/transfers/{id}
    │
    ├─ Redis GET "transfer:status:{id}"
    │   ├─ HIT → return cached TransferResponse
    │   └─ MISS ↓
    ├─ PostgreSQL SELECT
    ├─ Redis SET "transfer:status:{id}" TTL=30s
    └─ Return response

Kafka Consumer (status change):
    └─ Redis DELETE "transfer:status:{id}"  ← cache invalidation
```

Graceful degradation: Redis ошибки логируются, но не ломают основной flow.

---

## 14. Notification Gateway (Go)

Файл: `notification-gateway/internal/consumer/consumer.go`

### Kafka Consumer (at-least-once)

```go
func (c *Consumer) Run(ctx context.Context) {
    for {
        msg, err := c.reader.FetchMessage(ctx)  // manual fetch
        // ...

        var event handler.NotificationEvent
        if err := json.Unmarshal(msg.Value, &event); err != nil {
            c.reader.CommitMessages(ctx, msg) // poison message → commit & skip
            continue
        }

        if err := c.handler.Handle(ctx, event); err != nil {
            continue // don't commit → will retry on next fetch
        }

        c.reader.CommitMessages(ctx, msg) // commit only on success
        metrics.MessagesProcessed.Inc()
    }
}
```

### Event Structure

```go
type NotificationEvent struct {
    TransferID       string   `json:"transfer_id"`
    SenderID         string   `json:"sender_id"`
    EventType        string   `json:"event_type"`
    NotificationText string   `json:"notification_text"`
    Channels         []string `json:"channels"` // ["push", "sms"]
}
```

### Delivery Handler (Router Pattern)

Файл: `notification-gateway/internal/handler/handler.go`

```go
func (h *DeliveryHandler) Handle(ctx context.Context, event NotificationEvent) error {
    var attempted, failed int

    for _, ch := range event.Channels {
        s, ok := h.router.Get(ch) // find sender for channel
        if !ok { continue }       // unknown channel → skip

        attempted++
        err := s.Send(ctx, notification)
        if err != nil {
            failed++
            metrics.DeliveryTotal.WithLabelValues(ch, "failure").Inc()
        } else {
            metrics.DeliveryTotal.WithLabelValues(ch, "success").Inc()
        }
    }

    // ALL failed → return error (Kafka consumer won't commit → retry)
    if attempted > 0 && failed == attempted {
        return fmt.Errorf("all %d delivery channels failed", failed)
    }
    return nil // partial success = OK
}
```

Sender interface + mock-реализации (PushSender, SMSSender):
```go
type Sender interface {
    Send(ctx context.Context, notification Notification) error
    Channel() string
}
```

### Prometheus Metrics (порт 8086)
- `notification_delivery_total{channel, status}` — counter
- `notification_delivery_duration_seconds{channel}` — histogram
- `notification_consumer_messages_processed_total` — counter

---

## 15. Error Handling: RFC 9457 Problem Details

Файл: `api/error/GlobalExceptionHandler.kt`

Все ошибки возвращаются в формате RFC 9457:

```json
{
  "type": "https://api.transferhub.com/errors/unsupported-corridor",
  "title": "Unsupported Corridor",
  "detail": "Corridor US→BR is not supported",
  "status": 422,
  "instance": "/api/v1/transfers",
  "traceId": "abc-123",
  "timestamp": "2026-03-06T12:00:00Z"
}
```

Иерархия исключений:
```
BusinessException (base, с statusCode + errorType)
  ├─ MinimumAmountException (422)
  ├─ UnsupportedCorridorException (422)
  ├─ UnsupportedDeliveryMethodException (422)
  ├─ RecipientNotFoundException (404)
  ├─ TransferNotFoundException (404)
  ├─ QuoteExpiredException (422)
  ├─ QuoteCorridorMismatchException (422)
  └─ PricingUnavailableException (503)
```

Другие handlers:
- `MethodArgumentNotValidException` → 400 + `violations[]` array
- `ObjectOptimisticLockingFailureException` → 409 Conflict
- `MissingRequestHeaderException` → 400
- Generic `Exception` → 500 + traceId

---

## 16. Cursor-based Pagination

Файл: `service/TransferService.kt`

Cursor = Base64(JSON(`{c: createdAt, i: id}`)) — opaque для клиента.

```kotlin
fun listTransfers(senderId: UUID, cursor: String?, size: Int) {
    val transfers = if (cursor == null) {
        // Первая страница: JPQL ORDER BY created_at DESC, id DESC
        transferRepository.findBySenderIdFirstPage(senderId, PageRequest.of(0, size + 1))
    } else {
        val (cursorCreatedAt, cursorId) = decodeCursor(cursor)
        // Native SQL с row-value comparison:
        // WHERE sender_id = :senderId AND (created_at, id) < (:cursorCreatedAt, :cursorId)
        transferRepository.findBySenderIdAfterCursor(senderId, cursorCreatedAt, cursorId, size + 1)
    }

    val hasMore = transfers.size > size
    val page = if (hasMore) transfers.take(size) else transfers
    val nextCursor = if (hasMore) encodeCursor(page.last().createdAt, page.last().id) else null
    // ...
}
```

Fetch `size + 1` записей — если пришло больше, значит `hasMore = true`.

---

## 17. Infrastructure: Docker Compose

Файл: `infra/docker/docker-compose.yml`

```yaml
services:
  postgres:    # PostgreSQL 16, port 5432
  mongo:       # MongoDB 7, port 27017
  redis:       # Redis 7, port 6379
  kafka:       # Confluent Kafka 7.6 (KRaft), ports 9092/29092
  consul:      # HashiCorp Consul 1.18, port 8500
  kafka-init:  # One-shot: creates all Kafka topics

  transfer-service:     # port 8080
  outbox-service:       # port 8081
  pricing-service:      # port 8082 (HTTP), 9090 (gRPC)
  mock-payment-service: # port 8083
  mock-payout-service:  # port 8084
  notification-gateway: # port 8085 (health), 8086 (metrics)
```

### Kafka Topics (create-topics.sh)

| Topic | Partitions | Retention | Producer | Consumer |
|---|---|---|---|---|
| `transfers.payment.requested` | 6 | 7d | Outbox Service | Mock Payment |
| `transfers.payout.requested` | 6 | 7d | Outbox Service | Mock Payout |
| `transfers.payment.refund.requested` | 6 | 7d | Outbox Service | Mock Payment |
| `payments.payment.captured` | 6 | 7d | Mock Payment | PaymentEventConsumer |
| `payments.payment.failed` | 6 | 7d | Mock Payment | PaymentEventConsumer |
| `payments.payment.refunded` | 6 | 7d | Mock Payment | PaymentEventConsumer |
| `payouts.payout.completed` | 6 | 7d | Mock Payout | PayoutEventConsumer |
| `payouts.payout.failed` | 6 | 7d | Mock Payout | PayoutEventConsumer |
| `notification.delivery` | 6 | 7d | Outbox Service | Notification Gateway |

---

## 18. Technology & Patterns Summary

| Паттерн | Технология | Где используется |
|---|---|---|
| **Transactional Outbox** | PostgreSQL + polling | Transfer + OutboxEvent в одной транзакции |
| **Saga Choreography** | Kafka events | Payment → Payout → Completion / Refund |
| **State Machine** | Kotlin sealed class | TransferStatus с валидацией переходов |
| **Idempotency** | X-Idempotency-Key header + DB | POST /transfers — защита от дублей |
| **Event Deduplication** | consumed_events table | Kafka consumers — exactly-once semantics |
| **Cache-Aside** | Redis (30s TTL) | GET /transfers/{id} — кеш ответа |
| **Circuit Breaker** | Resilience4j | gRPC вызовы к Pricing Service |
| **Distributed Lock** | Consul sessions + KV | create/update transfer — concurrency |
| **Optimistic Locking** | JPA @Version | Transfer entity — конкурентные обновления |
| **Retry + DLT** | Spring Kafka @RetryableTopic | Exponential backoff, dead letter topic |
| **Problem Details** | RFC 9457 / Spring ProblemDetail | Все HTTP ошибки в едином формате |
| **Cursor Pagination** | Base64(JSON) + native SQL | GET /transfers — row-value comparison |
| **gRPC** | protobuf + grpc-kotlin | Transfer → Pricing Service communication |
| **Router Pattern** | Go interfaces | Notification Gateway: channel → sender |
| **Graceful Shutdown** | Spring + Go signal.Notify | Все сервисы корректно завершаются |
| **Structured Logging** | SLF4J (Kotlin), zerolog (Go) | MDC traceId propagation |
| **Metrics** | Micrometer + Prometheus client | DLT counters, delivery histograms |
