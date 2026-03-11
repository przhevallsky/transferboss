# Sprint 2 — Kafka + Outbox + gRPC: Декомпозиция на блоки

## Sprint Goal

Outbox Service поллит таблицу и публикует события в Kafka. Transfer Service потребляет payment/payout events и обновляет статус. Pricing Service доступен по gRPC для Transfer Service.

**Что это даёт:** после Sprint 2 система становится по-настоящему distributed — три сервиса, асинхронное взаимодействие через Kafka, синхронное через gRPC, Transactional Outbox в действии.

---

## Обзор блоков

| Block | Содержание | Tasks | Зависимости |
|-------|-----------|-------|-------------|
| **B1** | Pricing Service (Ktor): REST endpoint /api/v1/quotes + Redis cache | S2-T11 (partial) | Sprint 0 Ktor skeleton |
| **B2** | Protobuf контракт + gRPC server в Pricing Service | S2-T10, S2-T11 | B1 |
| **B3** | gRPC client в Transfer Service + интеграция в createTransfer() | S2-T12 | B2 |
| **B4** | Outbox Service — скелет: Spring Boot app, DB connection, scheduled polling | S2-T01 | Sprint 1 outbox table |
| **B5** | Outbox Service — группировка, Kafka producer, маркировка SENT | S2-T02, S2-T03, S2-T04 | B4 |
| **B6** | Transfer Service Kafka consumer — payment events, state machine transitions | S2-T06, S2-T07 | B5 |
| **B7** | Idempotent consumer (processed_events) + Redis cache invalidation | S2-T08 | B6 |
| **B8** | Structured logging: logstash-logback-encoder, MDC traceId | S2-T17 | — |
| **B9** | Unit + Integration tests (Outbox→Kafka, consumer, gRPC) | S2-T05, S2-T09, S2-T13 | B5, B6, B3 |
| **B10** | Dockerfiles + Docker Compose + CI для Outbox и Pricing | S2-T14, S2-T15, S2-T16 | B5, B2 |

---

## Зависимости между блоками

```
B1 (Pricing REST) ──→ B2 (gRPC server) ──→ B3 (gRPC client in Transfer)
                                                    ↓
B4 (Outbox skeleton) ──→ B5 (Outbox Kafka) ──→ B6 (Transfer consumer)
                                                    ↓
                                              B7 (Idempotent consumer)

B8 (Structured logging) — независим, может делаться параллельно

B9 (Tests) — после B3, B5, B6
B10 (Docker/CI) — после B2, B5
```

Две параллельные ветки:
- **Pricing ветка:** B1 → B2 → B3
- **Outbox/Kafka ветка:** B4 → B5 → B6 → B7

Можно чередовать блоки из разных веток.

---

## Детали каждого блока

### Block 1 — Pricing Service (Ktor): REST + Redis

**Сервис:** `services/pricing-service/` (Ktor skeleton из Sprint 0)

**Что делать:**
- REST route: `GET /api/v1/quotes?source_country=US&dest_country=PH&send_currency=USD&receive_currency=PHP&send_amount=100&delivery_method=BANK_DEPOSIT&sender_id=...`
- Pricing logic (hardcoded MVP):
  - Fee: фиксированная по коридору (US→PH: $5.99, US→MX: $4.99, GB→IN: £3.99, US→IN: $5.49)
  - Exchange rate: hardcoded (US→PH: 56.20, US→MX: 17.15, GB→IN: 105.25, US→IN: 83.12)
  - receive_amount = (send_amount - fee) * exchange_rate
- Quote saved to Redis: key=`quote:{quoteId}`, TTL=30 sec, value=JSON
- Response с quoteId, amounts, rate, fee, expiresAt

**Результат:** `GET /api/v1/quotes` возвращает котировку, quote в Redis с TTL.

---

### Block 2 — Protobuf + gRPC Server (Pricing Service)

**Сервис:** `services/pricing-service/`

**Что делать:**
- `proto/pricing.proto` — GetQuote + ValidateQuote RPCs
- Gradle protobuf plugin для генерации Kotlin gRPC stubs
- gRPC server на отдельном порту (50051)
- PricingGrpcService — implements generated service interface
- ValidateQuote: проверяет quote_id в Redis → valid/expired/not_found

**Результат:** gRPC server запускается на :50051, отвечает на GetQuote и ValidateQuote.

---

### Block 3 — gRPC Client в Transfer Service

**Сервис:** `services/transfer-service/`

**Что делать:**
- Protobuf dependency (shared proto file или Maven artifact)
- PricingGrpcClient — Spring-managed bean, channel к pricing-service:50051
- Интеграция в TransferService.createTransfer():
  - Вызов ValidateQuote(quote_id) перед созданием
  - Если valid — берём receive_amount, exchange_rate, fee из quote
  - Если expired — throw QuoteExpiredException
- Замена hardcoded stubs из Sprint 1 реальными данными из Pricing
- Circuit breaker (Resilience4j) вокруг gRPC вызова

**Результат:** POST /api/v1/transfers теперь валидирует quote через gRPC → Pricing.

---

### Block 4 — Outbox Service: скелет

**Сервис:** `services/outbox-service/` (новый Spring Boot сервис)

**Что делать:**
- Spring Boot app: Application.kt, application.yml
- Подключение к PostgreSQL Transfer Service (read from outbox table)
- Spring `@Scheduled` poller: каждые 500ms
- Repository: `SELECT ... FROM outbox WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED`
- Пока просто логирует найденные события (без Kafka)

**Результат:** Outbox Service стартует, подключается к PostgreSQL, поллит outbox таблицу, логирует events.

---

### Block 5 — Outbox Service: Kafka Producer + группировка

**Сервис:** `services/outbox-service/`

**Что делать:**
- Kafka producer config: idempotence=true, acks=all, retries=3
- Группировка: из batch 100 строк → Map<entity_id, List<OutboxEvent>>
- Отправка в Kafka: topic=`transfer.events`, key=transfer_id (ordering guarantee)
- После успешной отправки: UPDATE outbox SET status='SENT', processed_at=now(), kafka_offset=...
- При ошибке отправки: UPDATE status='FAILED' (или оставляем PENDING для retry)
- Kafka topic config: partitions=6, replication-factor=1 (dev), retention=7d

**Результат:** Outbox events публикуются в Kafka. `kafka-console-consumer` показывает messages.

---

### Block 6 — Transfer Service: Kafka Consumer

**Сервис:** `services/transfer-service/`

**Что делать:**
- `@KafkaListener` для топика `payment.events` (симулируем события от Payment Service)
- PaymentEventConsumer: десериализация event → handlePaymentEvent()
- State machine transition: transfer.status.transitionTo(newStatus)
- Optimistic locking: UPDATE transfers SET status=:new, version=version+1 WHERE id=:id AND version=:expected
- Redis cache invalidation: transferCacheService.evict(transferId)
- Симулятор: отдельный endpoint или scheduled job, публикующий fake payment events для тестирования

**Результат:** При получении payment.captured → transfer status = PAYMENT_CAPTURED. Cache evicted.

---

### Block 7 — Idempotent Consumer + processed_events

**Сервис:** `services/transfer-service/`

**Что делать:**
- Flyway migration: CREATE TABLE processed_events (event_id UUID PK, processed_at TIMESTAMPTZ)
- Перед обработкой event: INSERT INTO processed_events ON CONFLICT DO NOTHING
- Если insert successful → обрабатываем
- Если conflict → уже обработано, skip
- Всё в одной @Transactional: insert processed_events + update transfer status
- Cleanup: scheduled job удаляет processed_events старше 7 дней

**Результат:** Повторная доставка того же event не дублирует обработку.

---

### Block 8 — Structured Logging

**Все сервисы:** transfer-service, pricing-service, outbox-service

**Что делать:**
- logstash-logback-encoder → JSON-формат логов
- MDC: traceId, spanId, transferId, service
- Каждая лог-запись: {"timestamp":"...","level":"INFO","service":"transfer-service","traceId":"...","message":"..."}
- Spring MVC filter: генерация traceId из header или UUID, кладётся в MDC
- Kafka: передача traceId через headers
- Logback config: logback-spring.xml с JSON encoder для prod, human-readable для dev

**Результат:** Все сервисы логируют в structured JSON с traceId.

---

### Block 9 — Tests (Unit + Integration)

**Что делать:**
- Outbox Service: integration test с Testcontainers (PostgreSQL + Kafka) — polling → message in topic
- Transfer Service consumer: integration test — publish event to Kafka → verify status change
- gRPC: integration test — Transfer Service → Pricing Service (embedded gRPC server в тесте)
- Unit tests: OutboxPoller, PaymentEventConsumer, PricingGrpcClient

**Результат:** 20+ тестов, покрытие ключевых flows.

---

### Block 10 — Dockerfiles + Docker Compose + CI

**Что делать:**
- Dockerfile для Outbox Service (Spring Boot, аналогично Transfer Service)
- Dockerfile для Pricing Service (Ktor — тоже JVM, аналогичный multi-stage)
- Docker Compose: добавить outbox-service, pricing-service, Kafka (+ Zookeeper)
- GitLab CI: parallel jobs для всех 3 сервисов

**Результат:** `docker compose up` поднимает всю систему. CI собирает и тестирует все сервисы.

---

## Итого Sprint 2

| Метрика | Значение |
|---------|----------|
| Блоков | 10 |
| Новый сервис | 1 (Outbox Service на Spring Boot) |
| Расширенный сервис | 1 (Pricing Service: REST → REST + gRPC) |
| Технологии | Kafka producer/consumer, gRPC/Protobuf, SELECT FOR UPDATE SKIP LOCKED |
| Паттерны | Transactional Outbox (полный), Idempotent Consumer, Circuit Breaker |
| Тесты | Unit + Integration с Testcontainers (PostgreSQL + Kafka + Redis) |
