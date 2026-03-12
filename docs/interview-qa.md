# TransferHub — Interview Questions & Answers

> Подготовка к техническим собеседованиям. Каждый ответ привязан к конкретным решениям, классам и метрикам из проекта TransferHub. Формат: 5–15 предложений, STAR где применимо.

---

## Секция 1: Общие вопросы о проекте

### Q1. «Расскажите о проекте, над которым вы работали»

TransferHub — платформа для международных денежных переводов (cross-border remittance), построенная на event-driven микросервисной архитектуре. Система обрабатывает переводы по 10+ валютным коридорам (US→PH, US→MX, GB→IN и др.) через полный lifecycle: от создания перевода и расчёта комиссий до проведения платежа, выплаты получателю и отправки нотификаций.

Платформа состоит из 8 сервисов: Transfer Service (Kotlin/Spring Boot) — ядро, управляющее жизненным циклом перевода; Pricing Service (Kotlin/Ktor) — расчёт котировок и комиссий через gRPC; Outbox Service — гарантированная доставка событий в Kafka; Notification Gateway (Go) — многоканальная доставка уведомлений; LLM Service — AI-ассистент на базе RAG и pgvector; Analytics ETL — конвейер аналитики через ClickHouse. Я отвечал за Transfer Service и Pricing Service end-to-end — от проектирования схемы данных до production-мониторинга.

Ключевые технические вызовы: обеспечение exactly-once семантики при обработке финансовых событий (Outbox Pattern), реализация Saga с компенсациями для цепочки payment→payout, расследование утечки памяти (unbounded ConcurrentHashMap → Caffeine), построение observability-стека (Prometheus + Grafana + Loki + Tempo) с end-to-end distributed tracing через REST и Kafka.

---

### Q2. «Какова архитектура системы? Нарисуйте high-level схему»

Архитектура — event-driven microservices с Kafka как центральным backbone. На высоком уровне: клиент обращается к Transfer Service через REST API (JWT-аутентификация), Transfer Service синхронно проверяет KYC через Identity Service (REST + Circuit Breaker) и валидирует котировку через Pricing Service (gRPC, ~5ms latency). Затем Transfer и Outbox Event сохраняются в PostgreSQL в одной транзакции (Outbox Pattern).

Outbox Service каждые 500мс поллит таблицу `outbox` (SELECT FOR UPDATE SKIP LOCKED) и публикует события в Kafka (23 топика, 12 партиций для высоконагруженных). Далее choreography-based Saga: Mock Payment слушает `transfers.payment.requested`, публикует `payments.payment.captured`; Mock Payout слушает `transfers.payout.requested`, публикует `payouts.payout.completed`. Transfer Service через `PaymentEventConsumer` и `PayoutEventConsumer` обновляет статус перевода на каждом шаге.

Для аналитики используем CQRS-подход: OLTP-данные в PostgreSQL, а копия событий через ETL-consumer идёт в ClickHouse (колоночная OLAP БД) для дашбордов в Grafana. Notification Gateway (Go, ~10MB Docker-образ) потребляет `notification.delivery` и доставляет уведомления по каналам (SMS, Push). Мониторинг: Prometheus (метрики) → Grafana (визуализация) → Loki (логи) → Tempo (трейсы) → Alertmanager (8+ алертов с routing P1/P2).

Почему event-driven, а не REST-to-REST? Три причины: decoupling (Pricing не знает о Notifications), replay (можно перечитать Kafka при добавлении нового consumer), resilience (при падении Notification Gateway события не теряются — consumer lag растёт, но данные в Kafka сохраняются 7 дней).

---

### Q3. «Сколько человек было в команде? Какая у вас была роль?»

Команда из 7 человек: Tech Lead (Daniel) — архитектурные решения и ADR; я — ownership над Transfer Service и Pricing Service end-to-end; два backend-разработчика — Outbox Service, Mock Payment/Payout, интеграционные тесты; Go-разработчик (Sergey) — Notification Gateway с нуля; DevOps-инженер (Maria) — CI/CD, Helm, Terraform, monitoring; QA (Alex) — тест-планы, приоритизация, арбитраж tech debt vs features.

Мой scope: проектирование domain model (sealed class `TransferStatus` с 14 состояниями и валидацией переходов), Flyway-миграции (7 версий), REST API с cursor-based pagination, gRPC-клиент к Pricing, Kafka consumers для Saga, SSE через Redis Pub/Sub, JWT-аутентификация с RBAC, Redis rate limiting (sliding window, 100 req/min), Caffeine/Redis cache, интеграция с Unleash feature flags, расследование memory leak, RAG pipeline для LLM Service.

Процесс: двухнедельные спринты, Planning/Daily/Review/Retro, velocity ~25 SP. Code review с правилом 4 часов (от создания MR до первого review). Definition of Done: код + тесты + миграции + документация.

---

### Q4. «Какой технологический стек и почему?»

**Kotlin 1.9.25 / Java 21** — null safety на уровне type system (никаких NPE), data classes для domain model, sealed classes для state machine (`TransferStatus`), coroutines для асинхронного gRPC и MongoDB. Java 21 — LTS с virtual threads, ZGC, pattern matching.

**Spring Boot 3.3.4** — ecosystem: Spring Data JPA, Spring Kafka, Spring Security (OAuth2 Resource Server), Spring Actuator. Для Transfer Service critical — транзакционность `@Transactional`, интеграция с Flyway, Testcontainers.

**Ktor 2.3.12** — для Pricing Service, потому что он stateless и CPU-bound (расчёт котировок). Ktor легче Spring Boot (~40MB vs ~200MB Docker image), нативная поддержка coroutines, DSL-based routing. Trade-off: меньше ecosystem (нет Spring Data), но для Pricing это не нужно — MongoDB через coroutine driver, Redis через Lettuce.

**Go 1.23** — для Notification Gateway: минимальный footprint (~10MB scratch image), горутины для concurrent Kafka consumption, нет JVM overhead. Выбрали Go, а не Kotlin, потому что Gateway — high-throughput fan-out (1 event → N notifications), где GC-паузы JVM нежелательны.

**Kafka 7.6.0 (KRaft)** — guaranteed delivery, partitioning (ordering по transfer_id), 7-day retention, DLT для dead letters. KRaft mode — без Zookeeper, упрощает операции.

**PostgreSQL 16 + pgvector** — ACID для финансовых данных, jsonb для гибких полей (bank_details), pgvector для RAG-embeddings. **MongoDB 7** — corridor configs в Pricing (schema-less, частые изменения). **Redis 7** — кэш (TTL 30s для котировок), Pub/Sub для SSE, sliding window для rate limiting. **ClickHouse 24.1** — columnar OLAP для аналитики (100x быстрее PostgreSQL на агрегациях).

---

### Q5. «Какой самый сложный технический вызов?»

**Situation:** При нагрузочном тестировании Transfer Service обнаружили, что heap memory монотонно растёт: 70% через 12 часов, 85% через 24 часа, OOM kill через 36 часов. Grafana alert `HighMemoryUsage` сработал на threshold 80%.

**Task:** Найти root cause утечки памяти и исправить без downtime, пока staging-окружение деградирует.

**Action:**
1. Снял heap dump через `jcmd <pid> GC.heap_dump` и проанализировал в Eclipse MAT (Memory Analyzer Tool).
2. MAT показал: `ConcurrentHashMap$Node` — 1.2 GB retained heap, 4.2M entries. Все узлы принадлежат `TransferStatusCache` — unbounded `ConcurrentHashMap<UUID, String>`, куда при каждом `transitionStatus()` добавлялся entry, но никогда не удалялся.
3. Математика: ~100K transfers/day × 350 bytes/entry = 35 MB/day роста. За 6 недель работы — 1.47 GB мёртвых данных.
4. Заменил `ConcurrentHashMap` на Caffeine cache: `maximumSize(10_000)`, `expireAfterWrite(5 min)`, `recordStats()` + Micrometer metrics.
5. Добавил `CaffeineCacheMetrics.monitor()` для мониторинга hit ratio и estimated size в Grafana.

**Result:** Heap стабилизировался на 450 MB (vs 2.1 GB), GC pause p99 упал с 520ms до 45ms, hit ratio 82%. Добавили soak-тест (4 часа sustained load) в CI и правило в code review checklist: «Are all in-memory collections bounded?».

---

### Q6. «Расскажите про случай, когда пришлось кардинально изменить решение»

**Situation:** В Sprint 3 использовали Spring Kafka `@RetryableTopic` для обработки ошибок в Notification consumer. При retry сообщение уходило в retry-topic и возвращалось обратно. Но QA заметил: уведомления приходят в неправильном порядке — «Перевод завершён» перед «Оплата подтверждена».

**Task:** Сохранить ordering нотификаций (Event A перед Event B для одного transfer_id) при retry failures.

**Action:** `@RetryableTopic` не сохраняет ordering: при retry Event A уходит в retry-topic, а Event B обрабатывается сразу из основного топика. Разработали паттерн Redirect & Retry:
1. При ошибке обработки Event A для transfer_123: добавляем transfer_123 в redirect set (Redis), отправляем Event A в retry-topic.
2. Когда приходит Event B для transfer_123: проверяем redirect set → transfer_123 есть → перенаправляем Event B тоже в retry-topic (не обрабатываем напрямую).
3. Retry consumer обрабатывает Event A → успех → Event B → успех → очищает redirect для transfer_123.

Согласовали дизайн с Notifications-командой на sync-встрече, написали ADR, реализовали через TDD.

**Result:** Нотификации всегда приходят в правильном порядке. Паттерн переиспользован для Payout consumer. Trade-off: увеличенная latency при ошибках (retry delay), но ordering для финансовых нотификаций критичнее скорости.

---

### Q7. «Как вы обеспечиваете качество кода?»

Четыре уровня защиты:

1. **Code Review (правило 4 часов):** Каждый MR проходит review в течение 4 часов от создания. Reviewer проверяет: бизнес-логику, edge cases, тесты, миграции, security (нет SQL injection, нет hardcoded secrets). Минимум 1 approve для merge.

2. **CI Pipeline:** На каждый push: compile → unit tests → integration tests (Testcontainers с реальным PostgreSQL, Kafka, Redis) → lint (ktlint) → build Docker image. Branch protection: CI must pass перед merge в main.

3. **Integration Tests (Testcontainers):** Не mock'аем БД — поднимаем реальный PostgreSQL 16 в контейнере, запускаем Flyway-миграции, проверяем что Hibernate `ddl-auto: validate` не ругается. Kafka tests используют embedded broker. Это спасло нас от бага: Hibernate 6 не распознавал `CHAR(N)` как `bpchar` в PostgreSQL — узнали на CI, а не в production.

4. **Definition of Done:** Код + тесты (unit + integration) + миграции + обновлённая документация + review approved + CI green. Без выполнения всех пунктов PR не merge'ится.

Дополнительно: Sprint 5 добавил PII masking в логах (`PiiMaskingConverter` — custom Logback encoder автоматически маскирует email, phone, card numbers), JWT аутентификацию (RS256), rate limiting (Redis sliding window). Sprint 6 — JaCoCo coverage reports для отслеживания test coverage.

---

### Q8. «Что бы вы сделали иначе, если бы начинали проект заново?»

**1. Schema Registry с первого дня.** Сейчас события в Kafka — plain JSON без schema validation. При изменении payload (добавили поле, переименовали) — consumer может сломаться. Confluent Schema Registry + Avro/Protobuf дали бы backward/forward compatibility и автоматическую генерацию DTO. Мы обошли это хорошей дисциплиной (версионируем payload вручную), но Schema Registry масштабируется лучше.

**2. Contract Tests вместо Mock-сервисов.** Mock Payment и Mock Payout эмулируют поведение реальных провайдеров, но их API может дрейфовать от реальности. Pact или Spring Cloud Contract дали бы provider-driven contract testing: если Payments-команда меняет формат события, наш тест упадёт до деплоя.

**3. Сразу Caffeine для in-memory кэшей.** `ConcurrentHashMap` для кэширования — это техдолг, который аукнулся memory leak'ом. С первого дня нужно было установить правило: любой in-memory кэш — только через Caffeine с maxSize и TTL. Теперь это в нашем code review checklist.

**4. Event Sourcing для Transfer.** Сейчас храним только текущее состояние (`transfers.status`). Если бы использовали event sourcing (append-only log состояний), имели бы полную историю переходов для аудита и дебага. Компромисс: ES сложнее в реализации и требует CQRS для чтения, но для финансовой системы аудит-трейл критически важен.

---

## Секция 2: Architecture & System Design

### Q9. «Почему микросервисы, а не монолит?»

Выбрали микросервисы по трём причинам, специфичным для TransferHub:

**1. Разные профили нагрузки.** Transfer Service — transactional, write-heavy (создание переводов, обновление статусов, Outbox). Pricing Service — compute-heavy, read-heavy (расчёт котировок, кэширование курсов). Notification Gateway — fan-out (1 событие → N каналов доставки). Масштабировать их нужно по-разному: Transfer Service 2-8 Pod'ов, Pricing 2-12, Notification 2-16.

**2. Polyglot stack.** Pricing Service на Ktor (легче Spring Boot, лучше для stateless compute), Notification Gateway на Go (минимальный overhead, горутины). В монолите мы бы были ограничены одним framework'ом.

**3. Команда 7 человек, 3 подкоманды.** Каждая работает над своим сервисом с независимым release cycle. Outbox Service деплоится независимо от Transfer Service — если нашли баг в polling-логике, не нужно пересобирать всё.

**Trade-offs, которые мы приняли:** Distributed transactions (решили через Outbox + Saga), operational complexity (решили через Docker Compose + Helm + centralized monitoring), network latency (решили через gRPC для hot path, Kafka для async). Для команды из 2 человек монолит был бы правильнее — overhead микросервисов не окупился бы.

---

### Q10. «Как сервисы взаимодействуют? Почему Kafka, а не REST?»

Три типа коммуникации, каждый для своего use case:

**gRPC (синхронный, hot path):** Transfer Service → Pricing Service для `ValidateQuote`. Protobuf binary serialization + HTTP/2 multiplexing дают ~5ms latency (vs ~15ms для REST/JSON). Pricing — единственный синхронный вызов на критическом пути создания перевода. Proto-файл `pricing_service.proto` определяет контракт: `GetQuote`, `ValidateQuote`.

**REST (синхронный, external/admin):** Внешний API Transfer Service (`/api/v1/transfers`), KYC-проверка через Identity Service. REST выбран для external API потому что клиенты (мобильные приложения, BFF) ожидают REST+JSON, и Swagger/OpenAPI упрощает интеграцию.

**Kafka (асинхронный, backbone):** Все остальное — 23 топика. Payment requested, payment captured, payout initiated, payout completed, notifications, analytics events. Почему не REST для Saga? Три причины:
1. **Decoupling:** Transfer Service публикует `transfers.payment.requested` и не знает, кто его прочитает. Можно добавить audit-consumer без изменения кода.
2. **Durability:** При падении Payout Service сообщения сохраняются в Kafka (7 дней retention). Когда сервис поднимется — обработает backlog (consumer lag).
3. **Ordering:** Key = transfer_id → все события для одного перевода идут в одну партицию → ordering гарантирован внутри partition.

Trade-off: eventual consistency (данные в разных сервисах рассинхронизированы на секунды-минуты). Для финансовой системы это приемлемо: клиент видит статус перевода через SSE в реальном времени, а бэкенд-согласованность достигается Saga'ой.

---

### Q11. «Как обеспечиваете консистентность между сервисами?»

Нет distributed transactions (2PC) — они плохо масштабируются и создают single point of failure. Вместо этого — комбинация паттернов:

**1. Outbox Pattern (Transactional Outbox):** Transfer и Outbox Event сохраняются в одной PostgreSQL-транзакции (`TransferService.createTransfer()`, аннотация `@Transactional`). Если commit прошёл — обе записи в БД. Если rollback — ни одна. Outbox Service отдельно поллит и публикует в Kafka. Гарантия: событие в Kafka будет тогда и только тогда, когда бизнес-данные записаны в БД.

**2. Choreography Saga (Eventual Consistency):** Перевод проходит через цепочку: CREATED → PAYMENT_PENDING → PAYMENT_CAPTURED → PAYOUT_PENDING → COMPLETED. Каждый шаг — отдельный сервис, коммуникация через Kafka. Если Payout fails → запускается компенсация: PAYOUT_FAILED → REFUND_PENDING → REFUNDED. `PayoutEventConsumer` при получении `payout.failed` создаёт outbox event `transfers.payment.refund.requested`.

**3. Idempotent Consumers:** Таблица `consumed_events` (event_id UUID PK) — перед обработкой проверяем, не обрабатывали ли мы это событие раньше. Одна транзакция: INSERT в consumed_events + UPDATE transfer status. Если event_id уже есть — skip. Это защищает от дублей при Kafka rebalance или retry.

**4. Optimistic Locking:** Колонка `version` в таблице `transfers`. При конкурентном обновлении (два consumer'а одновременно пытаются обновить статус) — один получит `OptimisticLockException` и retry.

---

### Q12. «Что такое Outbox Pattern и зачем?»

Проблема: при создании перевода нужно записать данные в PostgreSQL И отправить событие в Kafka. Если записали в БД, но crash перед отправкой в Kafka — событие потеряно (Payment Service не узнает о переводе). Если отправили в Kafka, но crash перед commit в БД — данных нет, а событие уже доставлено (phantom event).

Это классическая проблема **dual write** — нельзя атомарно записать в два разных хранилища без distributed transaction.

**Решение — Outbox Pattern:** пишем в одну транзакцию и бизнес-данные, и событие (в таблицу `outbox`). Отдельный процесс (Outbox Service) читает pending события и публикует в Kafka.

Наша реализация:
1. `TransferService.createTransfer()` в `@Transactional`: `transferRepository.save(transfer)` + `outboxEventRepository.save(outboxEvent)` — одна транзакция.
2. `OutboxPollingScheduler` (`outbox-service`) каждые 500мс: `SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100`.
3. `OutboxPublisher` группирует по entity_id (Kafka key), публикует в соответствующий topic, обновляет status = SENT с kafka_offset.
4. `FOR UPDATE SKIP LOCKED` — если другой инстанс Outbox Service уже обрабатывает batch, текущий инстанс берёт следующие записи (no contention, no deadlocks).

Гарантии: at-least-once delivery (при crash повторная обработка pending events). Idempotent consumers на стороне получателей обеспечивают effectively-once.

---

### Q13. «Как реализовали Saga? Choreography или Orchestration?»

**Choreography-based Saga** — каждый сервис реагирует на события и публикует свои. Нет центрального координатора.

**Почему choreography, а не orchestration?**
- Transfer Service не должен знать внутренние детали Payment и Payout (SRP)
- Добавление нового шага (например, Compliance check) — это добавление нового consumer'а, без изменения координатора
- Нет single point of failure (orchestrator)
- Trade-off: сложнее отследить полный flow (решили через distributed tracing — Tempo)

**Happy Path:**
```
Transfer Service → Kafka: transfers.payment.requested (key=transfer_id)
Mock Payment → Kafka: payments.payment.captured
PaymentEventConsumer → DB: status=PAYMENT_CAPTURED → Outbox: transfers.payout.requested
Mock Payout → Kafka: payouts.payout.completed
PayoutEventConsumer → DB: status=COMPLETED
```

**Compensation (Payout Failed):**
```
Mock Payout → Kafka: payouts.payout.failed (reason: INVALID_ACCOUNT)
PayoutEventConsumer → DB: status=PAYOUT_FAILED → Outbox: transfers.payment.refund.requested
Mock Payment → Kafka: payments.payment.refunded
PaymentEventConsumer → DB: status=REFUNDED
```

State machine с валидацией переходов (`TransferStatus` sealed class): каждый статус знает допустимые переходы. `transitionTo()` бросает `IllegalStateTransitionException` при невалидном переходе (например, COMPLETED → PAYMENT_PENDING).

Consumer'ы: `PaymentEventConsumer` (4 retry, exponential backoff 30s base, DLT после исчерпания), `PayoutEventConsumer` (аналогично). DLT-сообщения мониторятся Prometheus-алертом `DLTMessagesPresent`.

---

### Q14. «Что будет, если один из сервисов упадёт?»

Каждый сервис спроектирован с graceful degradation:

**Pricing Service down:** Transfer Service обращается через gRPC с Circuit Breaker (Resilience4j, instance `pricing-service`). После 5+ failures из 10 вызовов (50% threshold) CB переходит в OPEN → все запросы немедленно отклоняются с 503 (не тратим thread pool на timeout'ы). Через 30 секунд — HALF_OPEN, 3 пробных запроса. Если 2/3 успешны → CLOSED. Мониторинг: Prometheus алерт `CircuitBreakerOpen`, Grafana panel с CB state.

**Kafka down:** Transfer Service не может создать перевод (Outbox запишет в БД, но Outbox Service не сможет прочитать/отправить). Переводы копятся в `outbox` таблице со статусом PENDING. Когда Kafka восстановится — Outbox Service вычитает backlog. Consumer'ы (Payment, Payout) остановятся, consumer lag будет расти — но данные не потеряются (7 дней retention).

**Transfer Service down:** Kubernetes liveness probe (`/actuator/health`, 30s interval) детектирует проблему, Pod перезапускается. Rolling update: минимум 1 Pod всегда доступен. Kafka consumer'ы перебалансируются (CooperativeStickyAssignor — инкрементальный rebalance, не останавливает все партиции).

**PostgreSQL down:** Все сервисы зависящие от PG (Transfer, Outbox) — healthcheck fails, readiness probe negative, Kubernetes убирает из Service endpoints. Kafka backlog накапливается. После восстановления PG — consumer'ы обработают backlog, idempotent consumers предотвращают дубли.

**Redis down:** Rate limiting — fail-open (запросы пропускаются, `RateLimitFilter` ловит `RedisConnectionException`). Cache — fallback на БД (cache miss, не ошибка). SSE — перестаёт работать (Redis Pub/Sub), но REST API работает.

---

### Q15. «Почему PostgreSQL для transfers, а MongoDB для corridor configs?»

**Polyglot persistence** — каждая БД для своей задачи.

**PostgreSQL для transfers:**
- ACID-транзакции: Transfer + Outbox Event в одной транзакции (критично для Outbox Pattern)
- Сильная типизация: NUMERIC для денежных сумм (не float!), CHECK constraints для статусов (14 допустимых значений), UNIQUE для idempotency_key
- Индексы: composite `(sender_id, created_at DESC)` для cursor-based pagination, partial index `WHERE status NOT IN ('COMPLETED','FAILED','REFUNDED')` для мониторинга активных переводов
- Flyway-миграции: 7 версий, schema validation через Hibernate `ddl-auto: validate`
- pgvector extension: vector similarity search для RAG pipeline в LLM Service

**MongoDB для corridor configs в Pricing Service:**
- Schema-less: corridor configurations меняются часто (добавление стран, изменение fee tiers, новые delivery methods). В PostgreSQL каждое изменение — ALTER TABLE + migration. В MongoDB — просто upsert документа
- Nested documents: fee tiers — массив объектов `{min: 0, max: 500, fee: 4.99}` — нативно хранится в BSON
- Корутиновый драйвер: `mongodb-driver-kotlin-coroutine` v5.2.1 интегрируется с Ktor coroutines

**ClickHouse для аналитики:**
- Columnar storage: для запросов «объём переводов по коридорам за месяц» читает только 2 колонки из 17 → 50-100x быстрее PostgreSQL
- ReplacingMergeTree: дедупликация при replay из Kafka (at-least-once → effectively-once)
- LowCardinality: dictionary encoding для enum-like колонок (corridor, currency, status) — 10x сжатие

---

### Q16. «Как спроектирована схема данных?»

Таблица `transfers` — центральная сущность. Ключевые решения:

**1. Idempotency Key (UUID UNIQUE):** Каждый запрос на создание перевода содержит `X-Idempotency-Key` header. При повторном запросе с тем же ключом — возвращаем существующий перевод (не создаём дубль). Проверка: `transferRepository.findByIdempotencyKey()` перед insert.

**2. Optimistic Locking (version INTEGER):** При конкурентном обновлении (два Kafka consumer'а одновременно обновляют статус) — один получит `StaleObjectStateException`. Hibernate автоматически проверяет `WHERE version = :expectedVersion` при UPDATE.

**3. Cursor-based Pagination:** Не OFFSET/LIMIT (деградирует при глубоком пейджинге: 10K offset → sequential scan 10K строк). Вместо этого: `WHERE (created_at, id) < (:cursorCreatedAt, :cursorId) ORDER BY created_at DESC, id DESC LIMIT :size+1`. Cursor — Base64-encoded JSON `{c: "2026-01-15T...", i: "uuid"}`. +1 для has_more check.

**4. Separation of Concerns:** `transfers` (бизнес-данные) отделена от `outbox` (events), `idempotency_keys` (API dedup), `consumed_events` (consumer dedup), `recipients` (PII данных получателя). Каждая таблица — своя ответственность.

**5. Status как VARCHAR(30) с CHECK constraint**, не ENUM: PostgreSQL ENUM нельзя ALTER TYPE без downtime в некоторых версиях. VARCHAR + CHECK позволяет добавлять новые статусы через миграцию без блокировки таблицы.

**6. NUMERIC для денег**, не DECIMAL/FLOAT: `send_amount NUMERIC(18,4)` — точная арифметика без float rounding. В Kotlin — `BigDecimal`.

---

### Q17. «Почему Ktor для Pricing, а не Spring Boot?»

Pricing Service — stateless compute service: получает запрос (gRPC или REST), читает из Redis/MongoDB, считает, возвращает результат. Не нужны транзакции, JPA, Flyway, Spring Security.

**Ktor преимущества для этого use case:**
- **Lightweight:** ~40MB Docker image vs ~200MB для Spring Boot. Startup: ~2s vs ~8s. Важно для HPA autoscaling — новый Pod доступен за секунды.
- **Native coroutines:** Ktor построен на coroutines. MongoDB Kotlin Coroutine Driver + Ktor = естественная интеграция. В Spring Boot нужен `runBlocking` или WebFlux для async.
- **DSL Routing:** `routing { get("/quotes") { ... } }` — декларативно и компактно. Для 3-4 эндпоинтов Pricing Service — идеально.
- **Ktor + gRPC:** gRPC server на Netty (`grpc-netty-shaded`), embeddedServer параллельно с HTTP. Оба протокола в одном процессе.

**Trade-offs:**
- Нет Spring ecosystem (Spring Data, Spring Security). Для Pricing это ОК: Redis через Lettuce напрямую, MongoDB через coroutine driver, аутентификация не нужна (внутренний сервис, вызывается только из Transfer Service).
- Меньше community/документации. Для простого сервиса хватает.
- Не унифицировано с остальным стеком. Но polyglot — осознанный выбор: каждый сервис на лучшем для него инструменте.

---

### Q18. «Зачем gRPC между Transfer и Pricing?»

Transfer Service вызывает Pricing Service синхронно на критическом пути создания перевода. Каждый перевод = 1 gRPC-вызов `ValidateQuote`. Под нагрузкой — сотни вызовов в секунду.

**Почему gRPC, а не REST:**
- **Latency:** Protobuf binary serialization (~5ms) vs JSON (~15ms). 3x разница на hot path. При 1000 переводов/час — экономим 10 секунд суммарной latency.
- **Type Safety:** Proto-файл (`pricing_service.proto`) — строго типизированный контракт. Изменение API ломает компиляцию (protoc генерирует Kotlin stubs). REST/JSON — ошибка обнаружится только в runtime.
- **HTTP/2 Multiplexing:** Одно TCP-соединение, множество concurrent streams. Нет head-of-line blocking HTTP/1.1.
- **Streaming (future):** Если понадобится price streaming (real-time rate updates) — gRPC server-side streaming из коробки.

**Почему не gRPC для всего:**
- External API (клиенты) — REST+JSON: универсальность, Swagger/OpenAPI, мобильные приложения ожидают REST.
- Kafka events — Protobuf мог бы быть лучше JSON (schema evolution, compact), но выбрали JSON для простоты дебага (читаемость в Kafka UI). В ретроспективе — Schema Registry + Protobuf для Kafka было бы лучше.

---

### Q19. «Как устроен distributed locking через Consul?»

**Проблема:** Transfer Service работает в 2-8 Pod'ах. При создании перевода нужно проверить idempotency key и создать запись — race condition: два Pod'а одновременно проверяют `findByIdempotencyKey() == null`, оба создают перевод.

**Почему не PostgreSQL SELECT FOR UPDATE:**
- Lock на уровне БД: при 6+ Pod'ах — contention на строках, рост latency, потенциальные deadlock'и
- Lock на конкретный sender_id (не на всю таблицу), Consul — более гранулярный

**Реализация — Consul KV Store:**
`ConsulDistributedLockService` (`lock/ConsulDistributedLockService.kt`):
1. Создаёт Consul session (TTL 15 секунд) — если holder умирает, lock автоматически release через TTL
2. `PUT /kv/locks/transfer/sender/{senderId}/create?acquire={sessionId}` — атомарный acquire
3. Если lock занят — retry с exponential backoff (50ms → 100ms → 200ms, max 500ms), timeout 5 секунд
4. После выполнения операции — `PUT /kv/...?release={sessionId}`
5. Key prefix: `locks/transfer` — все lock'и Transfer Service в одном namespace

**Lock granularity:**
- Создание перевода: `locks/transfer/sender/{senderId}/create` — lock по sender, не по transfer
- Обновление статуса: `locks/transfer/transfer/{transferId}/status` — lock по конкретному переводу

**Fallback:** Если Consul недоступен — `ConsulDistributedLockService` бросает `LockAcquisitionException`, Transfer Service отвечает 503. Consul healthcheck в docker-compose, Kubernetes readiness probe.

---

### Q20. «Как вы решаете проблему dual write (БД + Kafka)?»

**Dual Write Problem:** Нужно записать данные в PostgreSQL И отправить событие в Kafka атомарно. Но это два разных хранилища — нет distributed transaction.

**Три варианта, которые рассматривали:**

**1. Send then Save (отправить, потом записать):**
- Отправили в Kafka → crash → данных в БД нет, но событие уже доставлено
- Consumer создаст side effect на несуществующие данные — broken state

**2. Save then Send (записать, потом отправить):**
- Записали в БД → crash → данные есть, но Kafka не знает
- Payment Service не получит запрос на оплату — перевод зависнет навсегда

**3. Outbox Pattern (наш выбор):**
- Записываем и данные, и событие в одну PostgreSQL-транзакцию
- Отдельный сервис (Outbox Service) читает pending events и публикует в Kafka
- Если Outbox Service crash — при перезапуске вычитает все PENDING events (at-least-once)
- Если Kafka недоступна — events копятся в outbox таблице, будут отправлены позже

**Альтернатива — Change Data Capture (Debezium):**
- CDC читает PostgreSQL WAL и публикует изменения в Kafka
- Преимущества: не нужен отдельный сервис (Outbox Service), меньше кода
- Почему отказались: дополнительная инфраструктура (Debezium Connect), сложнее дебажить (WAL → Kafka mapping), наша команда лучше знает Spring Kafka, чем Debezium. Для масштаба TransferHub Outbox Pattern достаточен.

Реализация: `OutboxPollingScheduler` с `FOR UPDATE SKIP LOCKED` — позволяет нескольким инстансам Outbox Service работать параллельно без contention. Batch size 100, interval 500ms — compromise между latency и throughput.
