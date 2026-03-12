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

---

## Секция 3: Kafka и обмен сообщениями

### Q21. «Как гарантируете порядок сообщений в Kafka?»

Ordering в Kafka гарантируется **внутри одной партиции**. Наш подход: Kafka key = transfer_id. Все события для одного перевода (CREATED → PAYMENT_CAPTURED → COMPLETED) попадают в одну партицию → consumer читает их строго по порядку.

Outbox Service группирует события по `entity_id` (который равен transfer_id) перед отправкой — `OutboxPublisher` использует `entity_id` как Kafka key в `ProducerRecord`. Это критично: если бы key был случайным, события для одного перевода разлетелись бы по разным партициям, и consumer мог бы обработать COMPLETED раньше PAYMENT_CAPTURED.

Для notification-топиков ordering ещё критичнее: уведомление «Перевод завершён» не должно приходить раньше «Оплата подтверждена». Здесь мы столкнулись с проблемой: `@RetryableTopic` нарушает ordering при retry (Event A уходит в retry-topic, Event B обрабатывается сразу). Решили через Redirect & Retry Pattern (см. Q22).

Trade-off: ordering гарантирован только внутри партиции → максимум один consumer на партицию в consumer group. Для `transfer.events` (12 партиций) — максимум 12 параллельных consumer'ов. Если нужно больше throughput — увеличиваем партиции, но это необратимая операция в Kafka.

---

### Q22. «Расскажите про Redirect & Retry Topic»

**Situation:** В Sprint 3 использовали Spring Kafka `@RetryableTopic` для Notification consumer. QA обнаружил: уведомления приходят в неправильном порядке. Клиент получал «Перевод завершён» перед «Оплата подтверждена».

**Task:** Обеспечить ordering нотификаций при retry failures, чего `@RetryableTopic` не гарантирует.

**Action:** Разработали паттерн Redirect & Retry с тремя компонентами:
1. `NotificationDeliveryConsumer` — основной consumer для топика `notification.delivery`. При ошибке доставки: добавляет `transferId` в redirect set (`ConcurrentHashMap<String, Boolean>`), отправляет сообщение в `notification.delivery.retry`.
2. Если приходит новое событие для того же `transferId` — проверяет redirect set → перенаправляет в retry-topic без попытки доставки. Так все события для одного перевода идут в retry-topic последовательно.
3. `NotificationRetryConsumer` (топик `notification.delivery.retry`, `max.poll.records=1`) — обрабатывает по одному. Прогрессивный backoff: 30s → 2min → 10min → 30min → 1h. После 5 неудач → `notification.delivery.dlt`. При успехе — `mainConsumer.clearRedirect(transferId)`.

**Result:** Нотификации всегда приходят в правильном порядке. Retry headers (`retry-count`, `original-timestamp`, `failure-reason`) позволяют мониторить retry-path в Grafana. Паттерн переиспользован для Payout consumer.

---

### Q23. «Что если consumer не может обработать сообщение?»

Два разных подхода в зависимости от consumer'а:

**@RetryableTopic (PaymentEventConsumer, PayoutEventConsumer):** 4 попытки с exponential backoff (base 30s, multiplier 10x, max 1 час). Spring Kafka автоматически создаёт retry-topics с суффиксами: `payments.payment.captured-0`, `-1`, `-2`, `-dlt`. Non-retriable исключения (`NonRetriableConsumerException`) обходят retry и идут сразу в DLT. `@DltHandler` логирует событие и инкрементирует счётчик `kafka.dlt.messages.total`.

**Manual Redirect & Retry (NotificationDeliveryConsumer):** 5 retry с прогрессивным delay (30s → 2min → 10min → 30min → 1h). Header `retry-count` инкрементируется при каждой попытке. После исчерпания — `notification.delivery.dlt`. `NotificationDltConsumer` извлекает headers и логирует с полным контекстом.

**Общий pipeline:** retry с backoff → после N попыток → DLT → Prometheus alert `DLTMessagesPresent` → PagerDuty → ручной разбор. DLT-сообщения не удаляются из Kafka (7 дней retention) — можно replay после исправления бага.

Ключевой принцип: **idempotent consumers** (таблица `consumed_events`) гарантируют, что при retry мы не обработаем событие дважды.

---

### Q24. «Как обеспечиваете idempotent consumer?»

Таблица `consumed_events` (Flyway V005): `event_id VARCHAR(128) PRIMARY KEY, consumer_group VARCHAR(128), topic VARCHAR(256), processed_at TIMESTAMPTZ`.

Паттерн в каждом consumer'е (пример — `PaymentEventConsumer`):
1. Парсим JSON, извлекаем `event_id`
2. `consumedEventRepository.existsByEventId(event.eventId)` — O(1) lookup по PK
3. Если `true` → `log.info("Duplicate event, skipping")` → return
4. Если `false` → обрабатываем событие (UPDATE transfer status) + `consumedEventRepository.save(ConsumedEvent(eventId, "transfer-service", topic))` — **в одной `@Transactional`**

Атомарность критична: INSERT в `consumed_events` + UPDATE `transfers.status` в одной PostgreSQL-транзакции. Если crash после UPDATE но до INSERT — транзакция откатится, при retry повторим обработку. Если crash после commit — event_id уже записан, retry будет пропущен.

Зачем нужен idempotent consumer, если Kafka и так доставляет сообщения? Kafka гарантирует at-least-once при `acks=all` + `enable.idempotence=true` на producer, но consumer может получить дубль при: rebalance (consumer умер, другой подхватил с last committed offset), retry из DLT, manual replay. В финансовой системе двойная обработка = двойной платёж.

---

### Q25. «Какие гарантии доставки (acks) и почему?»

**Producer (Outbox Service):** `acks=all` — сообщение считается отправленным только когда ВСЕ in-sync replicas (ISR) подтвердили запись. Это максимальная durability. `enable.idempotence=true` — защита от дубликатов при retry на стороне producer (Kafka producer ID + sequence number). `max.in.flight.requests.per.connection=5` — разрешает pipelining (5 batch'ей в полёте), но ordering сохраняется благодаря idempotence.

**Trade-off:** `acks=all` добавляет ~5ms latency по сравнению с `acks=1` (только leader). Для финансовых топиков это приемлемо: потеря события `payment.requested` означает зависший перевод и потерянные деньги клиента. 5ms latency — ничто по сравнению с этим риском.

**Batching:** `batch.size=16384` (16KB), `linger.ms=5` — Outbox Service агрегирует до 16KB или 5ms, затем отправляет batch. При low throughput — задержка 5ms. При high throughput — batch заполняется раньше.

**Consumer:** `auto-offset-reset=earliest` — при первом подключении или потере offset читаем с начала (не теряем события). Manual offset commit после обработки batch'а — если consumer crash, перечитает необработанные.

---

### Q26. «Как мониторите Kafka?»

Три уровня мониторинга:

**1. Consumer Lag (ключевая метрика):** Разница между последним offset'ом в партиции и committed offset consumer'а. Если lag растёт — consumer не справляется с нагрузкой. Prometheus собирает через Spring Kafka Micrometer (`spring.kafka.listener.observation-enabled: true`). Alert `KafkaConsumerLagHigh` при lag > 10K на любой consumer group.

**2. DLT Messages:** Счётчик `kafka.dlt.messages.total` (Counter) с tag `topic`. Любое сообщение в DLT — потенциальная потеря данных. Alert `DLTMessagesPresent` при count > 0 за 5 минут → P1 → PagerDuty.

**3. Producer Metrics:** `kafka.producer.record.send.latency` (Histogram) — время отправки. Резкий рост → проблемы с Kafka cluster или network. `kafka.producer.record.error` — ошибки отправки.

**Distributed Tracing:** `observation-enabled: true` на listener и template → Micrometer → OpenTelemetry → Tempo. W3C Trace Context пробрасывается через Kafka headers — можно увидеть полный trace от REST-запроса через Kafka consumers до PostgreSQL. В MDC добавляем `traceId` для корреляции логов в Loki.

**Grafana Dashboards:** Kafka panel с consumer lag по группам, throughput (events/sec), DLT trend. При alert — drill-down: метрика → Tempo trace → Loki logs.

---

### Q27. «Что такое @RetryableTopic и когда его НЕ стоит использовать?»

`@RetryableTopic` — Spring Kafka аннотация для автоматического retry через отдельные Kafka-топики. При ошибке обработки сообщение перемещается в retry-topic (`topic-0`, `topic-1`, ...), а затем в DLT (`topic-dlt`). Backoff настраивается: `attempts=4`, exponential с multiplier.

**Наша конфигурация в `PaymentEventConsumer`:**
```
attempts = 4
delay = 30000ms (30s)
multiplier = 10.0
maxDelay = 3600000ms (1 час)
topicSuffixingStrategy = SUFFIX_WITH_INDEX_VALUE
dltStrategy = FAIL_ON_ERROR
exclude = [NonRetriableConsumerException]
```

**Когда НЕ стоит использовать:**
1. **Когда важен ordering.** При retry Event A уходит в retry-topic, а Event B из основного топика обрабатывается сразу. Мы столкнулись с этим в Notification consumer — уведомления приходили в неправильном порядке. Заменили на ручной Redirect & Retry.
2. **Когда ошибка гарантированно не пройдёт при повторе.** `NonRetriableConsumerException` (bad JSON, unknown event type) — бессмысленно ретраить. Поэтому мы добавили `exclude` для таких исключений — сразу в DLT.
3. **Когда нужен fine-grained control over retry delay.** @RetryableTopic использует Kafka timestamps для delay — точность зависит от poll interval. Для notification retry нам нужна прогрессивная задержка (30s → 2min → 10min → 30min → 1h), что проще реализовать вручную.

---

### Q28. «Как работает CooperativeStickyAssignor?»

По умолчанию Kafka использует `RangeAssignor` — при rebalance (добавление/удаление consumer'а) ВСЕ партиции отбираются у всех consumer'ов и перераспределяются заново. Это «stop-the-world»: на время rebalance ни один consumer не обрабатывает сообщения.

`CooperativeStickyAssignor` (настроен в `application.yml` Transfer Service, Mock Payment, Mock Payout):
1. **Cooperative:** rebalance происходит в два раунда. В первом — consumer'ы отдают только те партиции, которые нужно перераспределить. Остальные продолжают обрабатывать. Во втором — перераспределённые партиции назначаются новому consumer'у.
2. **Sticky:** старается сохранить текущее назначение. Если consumer A обрабатывал партиции 0,1,2 и добавился consumer B — у A останутся 0,1, а B получит 2. Минимум миграций.

**Почему это важно для TransferHub:** При autoscaling (HPA добавляет Pod) eager rebalance остановил бы обработку ВСЕХ переводов на 10-30 секунд. С cooperative — только 1-2 партиции мигрируют, остальные продолжают работать. Для финансовой системы 30 секунд простоя = сотни задержанных переводов.

**Ограничение:** Go Notification Gateway (`segmentio/kafka-go`) не поддерживает CooperativeStickyAssignor — использует round-robin с stop-the-world rebalance. Это trade-off выбора Go-библиотеки.

---

### Q29. «Что будет, если Kafka полностью недоступна?»

**Создание переводов:** Transfer Service продолжит записывать в PostgreSQL (transfer + outbox event в одной транзакции). Outbox Service не сможет опубликовать события — они копятся в таблице `outbox` со статусом PENDING. Перевод будет в статусе PAYMENT_PENDING, но Payment Service не получит команду. Клиент увидит «перевод в обработке».

**Consumer'ы:** Все останавливаются — `PaymentEventConsumer`, `PayoutEventConsumer`, `NotificationDeliveryConsumer`, `AnalyticsEtlConsumer`. Текущие переводы зависают в промежуточных статусах. Но данные не теряются: Kafka хранит сообщения 7 дней (retention).

**При восстановлении Kafka:**
1. Outbox Service вычитает ВСЕ PENDING events и публикует batch'ами (batch-size 100, interval 500ms). Backlog может быть большим — зависит от времени простоя.
2. Consumer'ы перечитывают с last committed offset. Consumer lag будет высоким — Prometheus alert `KafkaConsumerLagHigh` сработает.
3. Idempotent consumers (`consumed_events` table) предотвращают двойную обработку при replay.

**Мониторинг:** Kafka broker health через Prometheus, алерт при broker count < 3 → P1 → PagerDuty. Grafana dashboard показывает Kafka cluster status, ISR count, under-replicated partitions.

**Mitigation:** Kafka cluster в production — 3 broker'а с replication factor 3. Потеря одного брокера — без impact. Потеря двух — degraded но работает. Полная недоступность — маловероятный сценарий, но система спроектирована для recovery.

---

### Q30. «Как данные попадают из Transfer Service в ClickHouse?»

CQRS-light pipeline: PostgreSQL (OLTP writes) → Kafka → ETL consumer → ClickHouse (OLAP reads).

1. При создании/обновлении перевода `TransferService` сохраняет OutboxEvent с `targetTopic = "transfer.events"`.
2. Outbox Service публикует JSON-event в Kafka topic `transfer.events`.
3. `AnalyticsEtlConsumer` (сервис `analytics-etl`, group `analytics-etl-group`) потребляет события и буферизирует в `CopyOnWriteArrayList<TransferAnalyticsRecord>`.
4. Flush по двум триггерам: buffer.size >= `batchSize` (100) ИЛИ `@Scheduled` каждые 10 секунд.
5. `ClickHouseClient.batchInsert()` — batch INSERT через JDBC (`com.clickhouse:clickhouse-jdbc:0.6.0`).
6. Таблица `transfers_analytics` — `ReplacingMergeTree` ORDER BY `(transfer_id, updated_at)` — дедупликация при replay.

**Почему не напрямую из PostgreSQL?** Decoupling: ClickHouse не зависит от PostgreSQL schema. Kafka как буфер: при недоступности ClickHouse события сохраняются в Kafka. Трансформация: ETL consumer преобразует JSON → structured record.

**Метрики:** `etl.events.consumed`, `etl.flushes.success`, `etl.flushes.errors` — мониторинг pipeline health.

---

## Секция 4: Базы данных и производительность

### Q31. «Расскажите про оптимизацию медленного SQL-запроса»

**Situation:** GET `/api/v1/transfers?page=500&size=20` — время ответа 2.8 секунды. При глубоком пейджинге (page > 100) latency деградировала экспоненциально.

**Task:** Обеспечить стабильное время ответа для списка переводов независимо от глубины пагинации.

**Action:** Проблема в OFFSET/LIMIT: `SELECT * FROM transfers WHERE sender_id = ? ORDER BY created_at DESC OFFSET 10000 LIMIT 20` — PostgreSQL всё равно сканирует 10000 строк, чтобы их пропустить. EXPLAIN ANALYZE показал Seq Scan с cost пропорциональным offset.

Заменили на cursor-based pagination:
```sql
SELECT * FROM transfers t
WHERE t.sender_id = :senderId
  AND (t.created_at, t.id) < (:cursorCreatedAt, CAST(:cursorId AS uuid))
ORDER BY t.created_at DESC, t.id DESC
LIMIT :limit
```

Row-value comparison `(created_at, id) < (cursor_created_at, cursor_id)` использует composite index `idx_transfers_sender_created (sender_id, created_at DESC)`. Cursor — Base64-encoded JSON `{c: "2026-01-15T...", i: "uuid"}`. Запрашиваем `size+1` записей — если вернулось больше, значит есть следующая страница.

**Result:** Стабильное время ответа ~15ms независимо от глубины пагинации. Нет OFFSET → нет sequential scan. Trade-off: нельзя прыгать на произвольную страницу (только next/prev), но для UX бесконечного скролла это идеально.

---

### Q32. «Зачем SELECT FOR UPDATE SKIP LOCKED?»

Используется в Outbox Service (`OutboxEventRepository`):
```sql
SELECT * FROM outbox
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

**FOR UPDATE** — блокирует выбранные строки до конца транзакции. Другие транзакции, пытающиеся SELECT FOR UPDATE тех же строк, будут ждать.

**SKIP LOCKED** — вместо ожидания, пропускает уже заблокированные строки. Это ключевое отличие: позволяет нескольким инстансам Outbox Service работать параллельно без contention.

Сценарий: 2 Pod'а Outbox Service поллят одновременно. Pod A берёт строки 1-100 (заблокированы). Pod B запрашивает — строки 1-100 заблокированы → SKIP LOCKED → берёт строки 101-200. Нет deadlock'ов, нет ожидания, максимальный throughput.

Без SKIP LOCKED: Pod B ждёт release строк Pod A → sequential обработка → bottleneck. Или deadlock, если оба блокируют перекрывающиеся наборы строк в разном порядке.

`ORDER BY created_at ASC` обеспечивает FIFO — старые события обрабатываются первыми. Partial index `idx_outbox_pending (created_at ASC WHERE status='PENDING')` ускоряет запрос.

---

### Q33. «Как делали миграцию MongoDB → PostgreSQL?»

**Situation:** Pricing Service хранил corridor configs в MongoDB. При переходе на PostgreSQL для унификации хранилищ нужна была zero-downtime миграция.

**Task:** Мигрировать данные из MongoDB в PostgreSQL надёжно, с возможностью retry и отката.

**Action:** Отдельный сервис `mongodb-migration` (`MigrationRunner.kt`):

1. **Distributed Lock:** `SELECT pg_try_advisory_lock(123456789)` — гарантирует, что только один инстанс выполняет миграцию. Если lock занят — graceful exit.
2. **Resume from checkpoint:** Таблица `migration_progress` хранит `last_processed_id`. При restart — продолжаем с последнего обработанного документа, не с начала.
3. **Batch processing:** Читаем из MongoDB по `batchSize` (500 по умолчанию). Для каждого batch — `INSERT INTO pricing_corridors ... ON CONFLICT DO UPDATE` (upsert). Сохраняем progress после каждого batch'а.
4. **Dry-run mode:** Флаг `migration.dry-run=true` — проходит все шаги, логирует, но не пишет в PostgreSQL. Для validation перед реальной миграцией.
5. **Type conversion:** MongoDB Decimal128 → Java BigDecimal (через explicit conversion, т.к. типы несовместимы напрямую).

**Result:** Миграция 50K corridor configs за 45 секунд. Advisory lock предотвратил конкурентные запуски. Dry-run выявил 3 документа с некорректными данными до реальной миграции. Upsert (ON CONFLICT DO UPDATE) сделал миграцию идемпотентной — безопасный повторный запуск.

---

### Q34. «Как устроено кэширование? Как решали cache invalidation?»

Три уровня кэширования:

**1. Caffeine (L1, in-process):** `TransferStatusCache` — `maximumSize(10_000)`, `expireAfterWrite(5 min)`. W-TinyLFU eviction (admission filter + LFU). Hit ratio 82% в production. Мониторинг через `CaffeineCacheMetrics.monitor()` → Grafana. Заменил unbounded ConcurrentHashMap (memory leak fix).

**2. Redis (L2, distributed):** `TransferCacheService` — key `transfer:status:{transferId}`, TTL 30 секунд. Cache-Aside pattern: GET → cache miss → DB query → cache put. Fail-open: при Redis failure — fallback на DB (returns null, не exception).

**3. Redis в Pricing Service:** `QuoteCacheService` — key `quote:{quoteId}`, configurable TTL. Сериализация через Kotlinx.serialization (не Jackson, т.к. Ktor ecosystem).

**Cache Invalidation стратегия:**
- **TTL-based (passive):** Все кэши с ограниченным TTL. Worst case — stale data на время TTL (5 min для Caffeine, 30s для Redis).
- **Event-driven (active):** При `transitionStatus()` — `transferStatusCache.put(saved.id, newStatus.value)` обновляет Caffeine. Redis evict при write.
- **Cache stampede protection:** Caffeine `.build()` с single-flight (один запрос грузит, остальные ждут). Redis — TTL jitter не реализован (trade-off: сложность vs масштаб).

Trade-off: допускаем stale reads (eventual consistency) в обмен на latency. Для GET transfer — допустимо: клиент увидит предыдущий статус, SSE push обновит через секунды.

---

### Q35. «Расскажите про утечку памяти»

**Situation:** Transfer Service на staging: heap memory растёт монотонно — 70% через 12 часов, 85% через 24, OOM kill через 36 часов. Grafana alert `HighMemoryUsage` на threshold 80%.

**Task:** Найти и устранить root cause без downtime.

**Action:**
1. `jcmd <pid> GC.heap_dump /tmp/heap.hprof` — снял heap dump на staging.
2. Eclipse MAT (Memory Analyzer Tool) → Dominator Tree: `ConcurrentHashMap$Node` — 1.2 GB retained, 4.2M entries.
3. Merge Shortest Paths to GC Root → `TransferStatusCache` — field типа `ConcurrentHashMap<UUID, String>`. Каждый вызов `transitionStatus()` добавлял entry, но удаления не было.
4. Математика: ~100K transfers/day × ~350 bytes/entry (UUID 36 bytes + String ~50 bytes + Node overhead) = ~35 MB/day. За 6 недель = 1.47 GB.
5. **Fix:** Заменил `ConcurrentHashMap` на Caffeine: `maximumSize(10_000)`, `expireAfterWrite(Duration.ofMinutes(5))`, `recordStats()`. Класс `TransferStatusCache.kt` — bounded cache с W-TinyLFU eviction и Micrometer metrics.

**Result:** Heap стабилизирован на 450 MB (было 2.1 GB). GC pause p99: 520ms → 45ms. Cache hit ratio: 82%. Добавили soak-test (4 часа sustained load) в CI и правило в checklist: «Are all in-memory collections bounded?»

---

### Q36. «Что такое idempotency key и как реализовали?»

Idempotency key — UUID, который клиент генерирует и передаёт в header `X-Idempotency-Key`. Гарантия: повторный запрос с тем же ключом вернёт тот же результат без side effects (не создаст дубль перевода).

**Реализация — два уровня:**

**1. В TransferService (domain level):** `transferRepository.findByIdempotencyKey(command.idempotencyKey)` — если найден → return existing transfer с `isNew=false`. Колонка `idempotency_key UUID UNIQUE` в таблице `transfers` (V001 migration).

**2. Таблица `idempotency_keys` (API level, V004 migration):** Хранит `key UUID PK, transfer_id FK, response_status INT, response_body JSONB, expires_at TIMESTAMPTZ`. Кэширует полный HTTP-ответ — при повторном запросе возвращаем точно такой же JSON с тем же HTTP-статусом (201 при первом, 200 при повторном).

**Атомарность:** Проверка и создание защищены Consul distributed lock (`locks/transfer/sender/{senderId}/create`). Lock по sender_id: два параллельных запроса от одного отправителя сериализуются. Запросы разных отправителей — параллельны.

**TTL:** `expires_at` = 24 часа. Старые записи можно чистить scheduled job'ом. UNIQUE constraint на `idempotency_key` — последняя линия защиты (DB-level constraint violation если lock не сработал).

---

### Q37. «Какие уровни изоляции транзакций использовали?»

PostgreSQL по умолчанию — **READ COMMITTED**, и мы его не меняли. Все `@Transactional` в TransferService используют default isolation level.

**Почему READ COMMITTED достаточен:**
- Каждая транзакция видит только committed данные (нет dirty reads)
- Для Outbox Pattern: `FOR UPDATE SKIP LOCKED` обеспечивает сериализацию доступа к pending events — isolation level не играет роли, т.к. row-level lock
- Для idempotency check: Consul distributed lock сериализует проверку ДО начала PostgreSQL-транзакции — race condition невозможен
- Optimistic locking (`@Version`) ловит конкурентные обновления: если два consumer'а одновременно обновляют статус, один получит `StaleObjectStateException`

**Почему НЕ SERIALIZABLE:**
- SERIALIZABLE в PostgreSQL реализован через SSI (Serializable Snapshot Isolation) — добавляет overhead на каждую транзакцию
- При конфликтах — serialization failure, нужен application-level retry
- Для нашего случая избыточно: distributed lock + optimistic locking = достаточная защита

**readOnly=true:** На `getTransfer()` и `listTransfers()` — подсказка Hibernate: не делать dirty checking, не flushing. PostgreSQL может использовать read-only replica (если настроена).

---

### Q38. «Как предотвращаете deadlock'и?»

Три стратегии:

**1. Consul Distributed Lock (application level):** Все операции, модифицирующие transfer, защищены lock'ом ДО начала PostgreSQL-транзакции. `executeWithLock("sender/{senderId}/create")` для создания, `executeWithLock("transfer/{transferId}/status")` для обновления. Один holder в один момент → нет конкурентных транзакций на одни данные → нет deadlock.

**2. FOR UPDATE SKIP LOCKED (Outbox Service):** Вместо `FOR UPDATE` (который ждёт и может создать deadlock) — `SKIP LOCKED` пропускает заблокированные строки. Нет ожидания → нет circular wait → нет deadlock.

**3. Optimistic Locking (safety net):** Колонка `version INT DEFAULT 0` в `transfers`. Hibernate: `UPDATE transfers SET status=?, version=version+1 WHERE id=? AND version=?`. Если version не совпал — `OptimisticLockException` → retry. Это last-line defense, если distributed lock не сработал (Consul down, race window).

**Почему не SELECT FOR UPDATE для transfers:** При 6-8 Pod'ах Transfer Service, каждый с Kafka consumer'ами — множество concurrent transactions на одну таблицу. `SELECT FOR UPDATE` на разных строках может создать deadlock если consumer A блокирует transfer_1 и ждёт transfer_2, а consumer B блокирует transfer_2 и ждёт transfer_1. Consul lock на конкретный transferId исключает это.

---

## Секция 5: Конкурентность и защита от дублирования

### Q39. «Как защищаетесь от дабл-клика?»

Три уровня защиты от повторных запросов:

**1. Frontend (X-Idempotency-Key):** Клиент генерирует UUID перед отправкой запроса. При повторном клике отправляется тот же UUID. API-контроллер извлекает header и передаёт в `CreateTransferCommand`.

**2. Distributed Lock (Consul):** Lock по `sender/{senderId}/create` — второй запрос того же пользователя будет ждать завершения первого. Timeout 5 секунд с exponential backoff (50ms → 100ms → 200ms, max 500ms).

**3. Idempotency Check (DB):** `transferRepository.findByIdempotencyKey()` — если перевод уже создан с этим ключом → return existing с HTTP 200 (не 201). UNIQUE constraint на колонке — final safety net на уровне БД.

**Сценарий:** Два идентичных POST-запроса с одним `X-Idempotency-Key` приходят одновременно. Request A захватывает Consul lock, Request B ждёт. A создаёт перевод, отпускает lock. B захватывает lock, проверяет `findByIdempotencyKey()` → found → return existing transfer.

Если Consul недоступен — `LockAcquisitionException` → 503 Service Unavailable. Лучше отказать, чем создать дубль перевода.

---

### Q40. «Optimistic vs Pessimistic locking — когда что?»

**В TransferHub используем оба:**

**Optimistic Locking (transfers table):** Колонка `version INT`, аннотация `@Version`. При UPDATE: `WHERE id=? AND version=?`. Если version не совпал — `StaleObjectStateException`. Используем для конкурентных обновлений статуса (два Kafka consumer'а одновременно).

**Когда Optimistic лучше:** Конфликты редки (большинство переводов обновляются одним consumer'ом в один момент). Не держим DB lock — другие транзакции работают параллельно. Retry на application level при конфликте.

**Pessimistic Locking (outbox table):** `SELECT FOR UPDATE SKIP LOCKED`. Используем для Outbox polling: множество consumer'ов конкурируют за одни и те же PENDING events. Конфликты частые → optimistic locking привёл бы к массовым retry.

**Когда Pessimistic лучше:** Высокая конкуренция за одни ресурсы. `SKIP LOCKED` — особый случай: не ждём release, берём другие строки. Это гибрид: pessimistic по механике, но без blocking.

**Consul Lock (distributed, application level):** Ни optimistic, ни pessimistic в классическом смысле. Distributed mutual exclusion через KV store. Используем когда нужно сериализовать операции ДО начала DB-транзакции (idempotency check + create transfer — должны быть атомарны).

---

### Q41. «Как обеспечивается атомарность проверки idempotency key?»

Проблема: проверить `findByIdempotencyKey() == null` и создать перевод нужно атомарно. Если два Pod'а проверяют одновременно — оба получат null → оба создадут перевод.

**Решение — три барьера:**

**Barrier 1 — Consul Distributed Lock:** `executeWithLock("sender/{senderId}/create")` сериализует ВСЕ создания переводов одного отправителя. Второй запрос ждёт завершения первого. Lock granularity по sender_id: запросы разных отправителей параллельны.

**Barrier 2 — DB Check:** Внутри lock: `transferRepository.findByIdempotencyKey()`. Если найден → return existing. Если нет → create. Проверка и создание в одной `@Transactional`.

**Barrier 3 — UNIQUE Constraint:** `idempotency_key UUID UNIQUE` в таблице `transfers` (V001 migration). Если все предыдущие барьеры не сработали (Consul down, race condition) — INSERT нарушит UNIQUE → `DataIntegrityViolationException` → 409 Conflict. Это последняя линия обороны.

Три барьера — defense in depth. В нормальном режиме работает Barrier 1 (Consul). Barrier 2 — для случая idempotency hit (возврат существующего перевода). Barrier 3 — катастрофический fallback.

---

### Q42. «Как несколько инстансов Outbox Service работают одновременно?»

Outbox Service деплоится в 2-4 Pod'а для высокой доступности. Все Pod'ы поллят одну таблицу `outbox` одновременно.

**Механизм — FOR UPDATE SKIP LOCKED:**
1. Pod A: `SELECT FROM outbox WHERE status='PENDING' ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED` → получает строки 1-100, блокирует их.
2. Pod B (параллельно): тот же запрос → строки 1-100 заблокированы → SKIP LOCKED → получает строки 101-200.
3. Pod A публикует batch 1-100 в Kafka, обновляет status=SENT, commit → строки разблокированы.
4. Pod B параллельно обрабатывает свой batch.

**Гарантии:**
- **No duplicates:** Каждая строка обрабатывается ровно одним Pod'ом (row-level lock).
- **No deadlocks:** SKIP LOCKED — нет ожидания, нет circular wait.
- **FIFO:** `ORDER BY created_at ASC` — старые события обрабатываются первыми.
- **At-least-once:** Если Pod crash после SELECT но до UPDATE status=SENT — lock release по timeout (PostgreSQL statement_timeout), строки снова PENDING → другой Pod подхватит.

**Polling interval:** 500ms (`outbox.polling.interval-ms`). Batch size: 100 (`outbox.polling.batch-size`). При 2 Pod'ах — throughput ~400 events/sec (200 per Pod per second). Масштабируется линейно с количеством Pod'ов.

**Метрики:** Outbox Service публикует `kafkaTopic` и `kafkaOffset` при успешной отправке — можно верифицировать, что событие действительно в Kafka.

---

## Секция 6: Docker и Kubernetes

### Q43. «Как устроен ваш Dockerfile?»

Все JVM-сервисы используют multi-stage build с двумя стадиями:

**Stage 1 — Builder:** `eclipse-temurin:21-jdk-alpine` (полный JDK для компиляции). Сначала копируем Gradle wrapper и конфигурацию (build.gradle.kts, settings.gradle.kts, gradle/) — этот слой кэшируется между билдами. Затем `./gradlew dependencies` — скачивание зависимостей (кэшируется если pom не изменился). Последним копируем исходный код и запускаем `./gradlew bootJar`.

**Stage 2 — Runtime:** `eclipse-temurin:21-jre-alpine` (только JRE, ~100MB вместо ~300MB). Создаём non-root пользователя (`appuser:appgroup`). Копируем JAR из builder stage. ENTRYPOINT с JVM-флагами: `-XX:MaxRAMPercentage=75.0` (использовать 75% от container memory limit), `-Djava.security.egd=file:/dev/./urandom` (быстрая инициализация SecureRandom).

**Notification Gateway (Go):** `golang:1.23-alpine` → `scratch` (пустой базовый образ). Статическая линковка (`CGO_ENABLED=0`), копируем CA-сертификаты для HTTPS. Итог: ~15MB образ vs ~200MB для JVM. Запуск от UID 65534 (nobody).

**HEALTHCHECK:** Все Dockerfiles содержат `HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- http://localhost:{port}/actuator/health/liveness || exit 1`.

**Layer optimization:** Порядок COPY инструкций от наименее до наиболее изменяемых файлов. Gradle config → dependencies → source code. При изменении только бизнес-кода — пересобирается только последний слой.

---

### Q44. «Чем liveness probe отличается от readiness?»

**Liveness probe** — «жив ли процесс?» Если fails 3 раза подряд — Kubernetes убивает Pod и перезапускает (restartPolicy). Путь: `/actuator/health/liveness`. Interval 10s, initialDelay 30s (даём JVM прогреться), failureThreshold 3.

**Readiness probe** — «готов ли принимать трафик?» Если fails — Kubernetes убирает Pod из Service endpoints (не отправляет запросы), но НЕ перезапускает. Путь: `/actuator/health/readiness`. Interval 5s, initialDelay 15s, failureThreshold 3.

**Startup probe** — «завершилась ли инициализация?» Пока startup probe не пройдёт — liveness и readiness не проверяются. Путь: `/actuator/health/liveness`, failureThreshold 30, period 1s. Даём JVM-сервису 30 секунд на старт (Flyway миграции, Spring context initialization, Kafka consumer group join).

**Пример неправильной настройки:** Если liveness probe проверяет зависимости (DB, Redis) — при падении PostgreSQL Kubernetes перезапустит ВСЕ Pod'ы Transfer Service. Это каскадный fail: Pod'ы рестартуются, не могут подключиться к DB, снова fail. Правильно: liveness = процесс жив, readiness = зависимости доступны. При падении DB — Pod'ы остаются живыми, но убираются из endpoints.

---

### Q45. «Как обеспечивается zero-downtime деплой?»

Rolling update strategy в Helm deployment:
```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0    # Ни один Pod не убивается до создания нового
    maxSurge: 1          # Создаём 1 дополнительный Pod
```

**Процесс:** K8s создаёт новый Pod → ждёт startup probe → ждёт readiness probe → убирает старый Pod. `maxUnavailable: 0` гарантирует: всегда есть минимум N работающих Pod'ов.

**preStop hook** (не реализован в текущем Helm, но обсуждался): `sleep 5` перед SIGTERM — даёт kube-proxy время обновить iptables rules. Без этого: Pod получает SIGTERM, kube-proxy ещё не обновил routing → некоторые запросы идут на умирающий Pod.

**Graceful shutdown:** `server.shutdown: graceful` в `application.yml` — Spring Boot ждёт завершения in-flight запросов (default 30s) перед остановкой. Kafka consumer отдаёт партиции через cooperative rebalance (не stop-the-world).

**Kafka consumer rebalance:** `CooperativeStickyAssignor` — при добавлении/удалении Pod'а только затронутые партиции мигрируют. Остальные consumer'ы продолжают работать без прерывания.

---

### Q46. «Как рассчитываете resource requests/limits для JVM?»

Формула: `container memory limit = Xmx + Metaspace + Thread stacks + Native memory + OS overhead`.

**Наши значения (Transfer Service):**
- Request: 512Mi (гарантированный minimum, scheduler учитывает при размещении)
- Limit: 1Gi (maximum, OOMKilled при превышении)
- CPU: 250m request, 1000m limit

**JVM tuning:** `-XX:MaxRAMPercentage=75.0` — JVM использует 75% от container limit для heap. При limit 1Gi: heap ≤ 768MB. Оставшиеся 256MB — Metaspace (~100MB), thread stacks (256KB × 200 threads = ~50MB), NIO buffers, JIT code cache.

**Outbox Service:** 256Mi/512Mi — меньше: нет REST API, нет cache, только polling + publish.

**Notification Gateway (Go):** 64Mi/128Mi — Go garbage collector более предсказуем, нет JVM overhead. ~15MB binary + goroutine stacks.

**HPA:** Transfer Service: 2-8 Pod'ов (CPU > 60%). Outbox Service: 1-3 Pod'а (kafka_consumergroup_lag > 1000). Pricing Service: 2-6 Pod'ов (CPU > 60%). HPA ограничение: для Kafka consumer'ов max Pod'ов ≤ количество партиций (12 партиций = max 12 consumer'ов).

---

### Q47. «Что такое Helm и зачем?»

Helm — пакетный менеджер для Kubernetes. Шаблонизирует YAML-манифесты, управляет релизами.

**Наша структура:** 4 Helm-чарта (transfer-service, outbox-service, pricing-service, notification-gateway). Каждый содержит: `Chart.yaml` (метаданные, version), `values.yaml` (defaults), `values-staging.yaml`, `values-production.yaml`, `templates/` (deployment, service, hpa, configmap).

**Зачем templates:** Один `deployment.yaml` с `{{ .Values.replicaCount }}`, `{{ .Values.image.tag }}`, `{{ .Values.resources.limits.memory }}`. Для staging: `helm install -f values-staging.yaml`, для production: `helm install -f values-production.yaml`. Без Helm — дублирование YAML для каждого окружения.

**Environment differences:**
- Staging: replicaCount 1, resources 256Mi/512Mi, HPA disabled
- Production: replicaCount 2, resources 512Mi/1Gi, HPA enabled (2-8 pods)

**Release management:** `helm upgrade --install` — idempotent deploy. `helm rollback` — откат к предыдущей версии за секунды. `helm history` — аудит всех деплоев.

---

### Q48. «Как управляете конфигурацией и секретами?»

Три уровня:

**1. ConfigMap (non-sensitive config):** Генерируется из Helm `templates/configmap.yaml`. Содержит: `SPRING_PROFILES_ACTIVE`, `SERVER_PORT`, `KAFKA_BOOTSTRAP_SERVERS`, `CONSUL_HOST`. Монтируется как environment variables в Pod.

**2. Kubernetes Secrets (sensitive, at-rest encryption):** Database passwords, JWT signing keys, API tokens. В production — через External Secrets Operator: Vault (HashiCorp) → ExternalSecret CRD → Kubernetes Secret. Разработчик не видит plaintext secrets.

**3. application.yml defaults + override:** Spring Boot property hierarchy: `application.yml` → environment variables → command-line args. Docker Compose использует environment override: `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/transferhub`. Kubernetes — через ConfigMap envFrom.

**Текущее состояние (development):** Secrets в docker-compose.yml как environment variables (plaintext). Для production — Vault + External Secrets. Trade-off: в dev-окружении приоритет — простота запуска (`docker compose up -d`), а не security.

---

## Секция 7: Observability

### Q49. «Какой observability стек?»

Четыре столпа в одном UI (Grafana):

**Prometheus (v2.50)** — метрики. Scrape interval 15s (global), 10s (services). Pull-model: Prometheus забирает метрики по HTTP (`/actuator/prometheus` для JVM, `/metrics` для Go). Alert rules в `alert-rules.yml`: 8+ правил с severity routing.

**Grafana (v10.3)** — визуализация. 3 дашборда: Transfer Service (RED metrics, business metrics), Kafka (consumer lag, DLT), Infrastructure (JVM heap, GC, HikariCP, Go goroutines). Datasources: Prometheus, Loki, Tempo, ClickHouse. Exemplars: клик по точке на графике → trace в Tempo.

**Loki (v2.9.4)** — логи. Promtail собирает Docker logs, парсит JSON (level, logger, message, traceId, spanId). Retention 7 дней. Запрос: `{service="transfer-service"} |= "error" | json | traceId != ""`. Корреляция: traceId из лога → ссылка на Tempo trace.

**Tempo (v2.3.1)** — distributed tracing. OTLP receivers (gRPC 4317, HTTP 4318). Spring Boot + Micrometer → OpenTelemetry → Tempo. W3C Trace Context propagation через HTTP headers и Kafka headers (`observation-enabled: true`). Service graph: визуализация зависимостей между сервисами.

**Единый workflow:** Grafana alert → click metric → exemplar → Tempo trace → click span → Loki logs (filtered by traceId). Весь путь от «что-то сломалось» до «вот конкретная строка лога» — в одном UI.

---

### Q50. «Как от алерта добраться до root cause?»

**Пример: Alert `HighLatency` (p99 > 500ms на Transfer Service).**

1. **Alert → Grafana:** Alertmanager отправляет в Slack (P2). Открываем Grafana, видим spike на панели «Latency p99» в дашборде Transfer Service.

2. **Metric → Exemplar:** На графике включены exemplars. Кликаем на точку spike — Grafana показывает `traceId=abc123`. Кнопка «View in Tempo».

3. **Trace → Tempo:** Видим полный trace: `POST /api/v1/transfers` (200ms) → `gRPC ValidateQuote` (15ms) → `PostgreSQL INSERT` (8ms). Но есть span `IdentityClient.checkKyc` — 480ms! Это bottleneck.

4. **Span → Logs:** Кликаем на span → «View in Loki». Запрос: `{service="transfer-service"} | json | traceId="abc123"`. Видим: `WARN IdentityClient - Slow response from identity service: 480ms, status=200`.

5. **Root Cause:** Identity Service деградирует. Проверяем его метрики — database connection pool exhausted. Fix: увеличить pool size или добавить circuit breaker timeout.

**Ключевое:** W3C Trace Context пробрасывается через ВСЕ коммуникации — REST, gRPC, Kafka. Один traceId связывает запрос через 3-4 сервиса. Без distributed tracing — grep по логам всех сервисов вручную.

---

### Q51. «Почему Loki, а не ELK?»

**Loki выбрали по трём причинам:**

**1. Стоимость:** Loki индексирует только метаданные (labels: service, level), не полный текст логов. Elasticsearch индексирует каждое слово (inverted index). При 50GB логов/день: Loki — ~5GB storage, ELK — ~50GB+. Для нашего масштаба (~5 сервисов, ~10GB/day) разница не критична, но принцип масштабируется.

**2. Единый UI:** Grafana — один интерфейс для метрик (Prometheus), логов (Loki), трейсов (Tempo). ELK — отдельный Kibana. Для команды из 7 человек один инструмент → меньше cognitive load, быстрее onboarding.

**3. Операционная простота:** Loki = один бинарник, local storage или S3. ELK = Elasticsearch (JVM, требует тюнинг), Logstash (pipeline config), Kibana. Три компонента vs один.

**Когда ELK лучше:** Full-text search по логам (Loki — regex по содержимому, не inverted index). Сложные аналитические запросы (Elasticsearch aggregations мощнее). Если уже есть ELK-экспертиза в команде.

---

### Q52. «Как distributed tracing через Kafka?»

**Проблема:** HTTP-трейсинг прост: W3C `traceparent` header передаётся от клиента к серверу. Но Kafka — асинхронный: producer отправляет, consumer читает через секунды/минуты. Как связать?

**Решение:** Spring Kafka `observation-enabled: true` на listener и template. Micrometer автоматически:
1. **Producer:** Извлекает текущий trace context и записывает в Kafka record headers (`traceparent`, `tracestate`).
2. **Consumer:** При получении сообщения читает headers, создаёт child span с parent из headers.

**Конфигурация:**
```yaml
spring.kafka:
  listener.observation-enabled: true
  template.observation-enabled: true
management.tracing:
  sampling.probability: 1.0   # 100% sampling
  propagation.type: w3c
```

**Результат в Tempo:** Trace начинается с `POST /api/v1/transfers` (Transfer Service), включает gRPC-вызов к Pricing, затем — Kafka publish через Outbox. Отдельный span для `PaymentEventConsumer.consume()` связан с тем же traceId. Можно увидеть полный lifecycle перевода: REST → DB → Kafka → Payment → Kafka → Payout → Complete.

**Ограничение:** Go Notification Gateway не использует OpenTelemetry SDK — трейсы обрываются на границе Go-сервиса. Trade-off: добавить OTEL Go SDK увеличило бы сложность для простого fan-out сервиса.

---

### Q53. «Какие алерты настроены?»

8 правил в `alert-rules.yml`, разделённых по severity:

**Critical (P1 → PagerDuty):**
- `CriticalErrorRate`: >5% HTTP 5xx за 2 минуты. Что-то серьёзно сломано.
- `CriticalConsumerLag`: Kafka lag >50K за 10 минут. Consumer мёртв или Kafka деградирует.

**Warning (P2 → Slack):**
- `HighErrorRate`: >1% HTTP 5xx за 5 минут. Начинаются проблемы.
- `HighLatency`: p99 >500ms за 5 минут. Деградация производительности.
- `CircuitBreakerOpen`: CB state = OPEN (immediate). Внешний сервис недоступен.
- `HighConsumerLag`: Kafka lag >10K за 5 минут. Consumer отстаёт.
- `DLTMessagesPresent`: Сообщения в Dead Letter Topic. Потенциальная потеря данных.
- `HighMemoryUsage`: JVM heap >80% за 5 минут. Возможная утечка памяти.

**Routing:** Alertmanager группирует по `alertname` + `service`. Group wait 30s (не спамить при массовом сбое). Repeat interval 4h (не будить ночью повторно).

---

### Q54. «Какие дашборды? Какие метрики ключевые?»

**3 дашборда в Grafana:**

**1. Transfer Service Dashboard:**
- **RED metrics:** Request Rate (req/s), Error Rate (%), latency p50/p95/p99
- **Business metrics:** Transfers Created by Corridor (US_PH, US_MX, GB_IN), Completed vs Failed ratio, Completion Time p95
- Ключевая метрика: **Error Rate** — если >1% → что-то не так

**2. Kafka Dashboard:**
- **Consumer Lag by Group** — самая важная: если растёт → consumer не справляется
- **Messages In Rate by Topic** — throughput
- **DLT Messages** — должен быть 0, любое значение > 0 → alert
- **Listener Duration p95/p99** — как долго обрабатывается одно сообщение

**3. Infrastructure Dashboard:**
- **JVM Heap** (Used vs Max) по сервисам — мониторинг утечек памяти
- **GC Pause Duration** — если p99 > 100ms → проблема
- **HikariCP Active/Pending Connections** — если pending > 0 → pool exhaustion
- **Go Goroutines** (Notification Gateway) — рост → goroutine leak

**ClickHouse Analytics Dashboard:** Transfer volume by corridor, revenue by corridor, success rate trend — бизнес-метрики для product team.

---

## Секция 8: Security

### Q55. «Как защищён API?»

Три уровня защиты:

**1. JWT Authentication (RS256):** `SecurityConfig.kt` настраивает NimbusJwtDecoder с RSA public key. Клиент получает JWT через `/auth/token`, передаёт в `Authorization: Bearer <token>`. JWT содержит claims: `sub` (userId), `roles` (array: SENDER, OPERATOR, ADMIN). Spring Security извлекает roles и маппит в `ROLE_*` authorities.

**2. RBAC (Role-Based Access Control):** Endpoint protection через `authorizeHttpRequests`:
- `/actuator/**`, `/swagger-ui/**`, `/api-docs/**`, `/auth/**` → permitAll
- `/api/v1/**` → authenticated (любая роль)
- Всё остальное → denyAll

Роли: SENDER (создание переводов, просмотр своих), OPERATOR (просмотр всех, изменение статусов), ADMIN (полный доступ). Контроллер дополнительно проверяет ownership: sender видит только свои переводы.

**3. Rate Limiting (Redis):** `RateLimitFilter` — sliding window через Redis Lua script. 100 req/min для authenticated, 20 req/min для anonymous (по IP). При превышении → 429 Too Many Requests + headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After: 60`.

**Session Policy:** STATELESS — сервер не хранит сессии. Каждый запрос несёт JWT. CSRF disabled (нет cookies → нет CSRF-атак).

**Error responses:** RFC 9457 Problem Details: при 401 и 403 — structured JSON с `type`, `title`, `status`, `detail`.

---

### Q56. «Как работает rate limiting?»

**Алгоритм — Redis Sliding Window** (Lua script для атомарности):

1. Key: `rate_limit:user:{username}` (authenticated) или `rate_limit:ip:{ip}` (anonymous)
2. Redis ZSET (sorted set): score = timestamp, member = unique request ID
3. `ZREMRANGEBYSCORE` — удалить все записи старше окна (60 секунд)
4. `ZCARD` — посчитать оставшиеся (текущий count за окно)
5. Если count < limit → `ZADD` новую запись → разрешить запрос
6. Если count >= limit → отклонить с 429
7. `EXPIRE` key на window duration — cleanup

**Почему Lua script:** Атомарность. Без Lua: между ZCARD и ZADD другой запрос может проскочить → превышение лимита. Lua script выполняется атомарно на Redis-сервере.

**Почему Sliding Window, а не Fixed Window:** Fixed Window (100 req/min, сброс каждую минуту) → burst: 100 запросов в последнюю секунду минуты + 100 в первую секунду следующей = 200 за 2 секунды. Sliding Window — равномерное распределение.

**Fail-open:** При `RedisConnectionException` — запрос пропускается (не блокируется). Лучше допустить превышение, чем отказать всем при падении Redis.

**Exclusions:** `/actuator` (health checks), `/auth` (получение токена), `/swagger-ui`, `/api-docs`.

---

### Q57. «Как маскируете PII в логах?»

`PiiMaskingConverter.kt` — custom Logback converter, зарегистрированный как `%piiMask(%msg)` в logback pattern.

**Паттерны маскирования:**
- **Email:** `user@example.com` → `u***@example.com` (первая буква + маска + домен)
- **Phone:** `+1234567890` → `***7890` (последние 4 цифры)
- **SSN:** `123-45-6789` → `***-**-6789`
- **Card number:** `4111-1111-1111-1234` → `****-****-****-1234` (последние 4 цифры)

**Как работает:** Regex-паттерны применяются к каждому log message перед записью. `ClassicConverter` из Logback: `convert(ILoggingEvent)` → проверяет message через цепочку regex → заменяет совпадения на маскированные значения.

**Зачем:** GDPR, PCI DSS compliance. Логи попадают в Loki, хранятся 7 дней, доступны всей команде. PII в логах — нарушение. Автоматическое маскирование лучше, чем надеяться, что разработчик не залогирует email.

**Trade-off:** Regex-маскирование на каждое лог-сообщение — overhead ~1-2μs. При 10K logs/sec — 10-20ms total, пренебрежимо.

---

### Q58. «Как управляете секретами?»

**Development (Docker Compose):** Environment variables в `docker-compose.yml`: `POSTGRES_PASSWORD=transferhub`. Допустимо для локальной разработки — secrets не покидают машину разработчика. `.env` файл в `.gitignore`.

**Production (Kubernetes):** Трёхуровневая модель:
1. **HashiCorp Vault:** Центральное хранилище секретов. Audit log, dynamic secrets (DB credentials с TTL), rotation.
2. **External Secrets Operator:** CRD `ExternalSecret` описывает какой секрет из Vault замапить в Kubernetes Secret. Operator синхронизирует автоматически.
3. **Kubernetes Secret:** Монтируется в Pod как environment variable или volume. At-rest encryption (KMS).

**RSA Keys (JWT):** Public key для JWT-верификации хранится в classpath (`classpath:keys/public.pem`) или filesystem path. Private key (для подписи) — только в Auth Service, хранится в Vault.

**Terraform:** S3 backend для state (содержит sensitive values). DynamoDB для state locking. State bucket с encryption at rest и TLS enforcement.

---

## Секция 9: Процессы и команда

### Q59. «Как была организована работа?»

**Scrum:** Двухнедельные спринты. Velocity ~25 Story Points. Ceremonies: Sprint Planning (2h), Daily Standup (15min), Sprint Review (1h), Retrospective (1h).

**Канбан-доска:** To Do → In Progress → Code Review → QA → Done. WIP limit: 2 задачи на разработчика (не начинать новое, пока текущее не в Review).

**Definition of Ready (для задач):** User story с acceptance criteria, tech dependencies определены, estimated (Fibonacci: 1,2,3,5,8,13).

**Definition of Done (для PR):** Код + unit tests + integration tests + Flyway-миграции (если есть schema changes) + обновлённая документация + code review approved + CI green.

**Branching:** Trunk-based development. Feature branches от main, PR → code review → merge в main. Нет develop/staging branches — main всегда deployable.

---

### Q60. «Как проходил code review?»

**Правило 4 часов:** От создания MR до первого review — максимум 4 часа в рабочее время. Это commitment команды, чтобы PR не висели днями.

**Что проверяет reviewer:**
1. **Бизнес-логика:** Правильно ли реализовано требование? Edge cases?
2. **Тесты:** Покрыты ли happy path + error paths? Есть ли integration test для нового endpoint?
3. **Security:** Нет SQL injection (parameterized queries only), нет hardcoded secrets, PII не логируется
4. **Performance:** Bounded collections? N+1 queries? Missing index?
5. **Migrations:** Backward-compatible? Нет destructive ALTER?

**Минимум 1 approve** для merge. Архитектурные решения (новый сервис, новый паттерн) — 2 approvals, один от Tech Lead (Daniel).

**Формат комментариев:** `nit:` (мелочь, не блокирует), `question:` (нужно объяснение), `blocker:` (не merge'ить пока не исправлено). Это снижает friction — reviewer сразу обозначает severity.

---

### Q61. «Расскажите про ретроспективу и улучшение»

**Situation:** На ретроспективе после Sprint 2 QA (Alex) отметил: два бага добрались до staging, которые были бы пойманы integration tests. Оба — несоответствие Hibernate entity mapping и Flyway-миграции.

**Task:** Предотвратить подобные баги в будущем.

**Action:** Обсудили на ретро, провели голосование. Решение:
1. Добавили `ddl-auto: validate` — Hibernate валидирует entity mapping против реальной схемы при старте. Если не совпадает — fail-fast.
2. Integration tests обязательны в Definition of Done: каждый PR с миграцией должен иметь Testcontainers-тест, который поднимает PostgreSQL, применяет миграции, валидирует маппинг.
3. CI gate: если integration tests не pass — merge blocked.

**Result:** За следующие 5 спринтов — 0 багов, связанных с schema mismatch. Testcontainers поймали баг с `CHAR(N)` vs `bpchar` (PostgreSQL internal type) до того, как он попал в staging. Время на CI увеличилось на 30 секунд (контейнер PostgreSQL), но ROI очевиден.

---

### Q62. «Как взаимодействовали с другими командами?»

Три точки взаимодействия:

**Payments Team:** Определяли контракт Kafka-событий (`payments.payment.captured`, `payments.payment.failed`). Формат: JSON с обязательными полями (`event_id`, `transfer_id`, `amount`, `timestamp`). Изменения — через RFC: описание, мотивация, migration plan, переходный период (обе версии payload поддерживаются 2 недели).

**Identity Team:** REST API контракт для KYC check. Мы — consumer, они — provider. Circuit breaker на нашей стороне (`identity-service`, `slowCallDuration=1s`). При деградации Identity — мы fail-fast (не зависаем).

**DevOps (Maria):** Совместная работа над Helm charts, CI pipeline, monitoring. Мы определяем требования (health endpoints, metrics, resource estimates), Maria реализует инфраструктуру. Prometheus alert rules — совместно: мы знаем business thresholds, Maria — routing и notification channels.

**Sync-встречи:** Раз в неделю — cross-team sync (30 min). Обсуждение: breaking changes, shared infrastructure, upcoming deployments.

---

### Q63. «Как работали с техническим долгом?»

**Отдельный бэклог:** Tech debt items в отдельной колонке на доске. Каждый item — с описанием проблемы, impact, estimated effort.

**Бюджет:** 15-20% каждого спринта — на tech debt. Из 25 SP: ~4-5 SP на tech debt. Если sprint overloaded фичами — tech debt сдвигается, но не более 2 спринтов подряд.

**Приоритизация (Alex как арбитр):** При конфликте «новая фича vs tech debt» — Alex (QA/PM) принимает решение на основе impact. Пример: memory leak в ConcurrentHashMap — P1 tech debt (OOM через 36 часов), выше любой фичи. Добавление Schema Registry — P3 (nice to have, работает и без него).

**Примеры tech debt, который мы закрыли:**
- ConcurrentHashMap → Caffeine (Sprint 6, P1)
- @RetryableTopic → Redirect & Retry (Sprint 4, P1)
- Missing integration tests → Testcontainers в DoD (Sprint 3, P2)
- Hardcoded corridor configs → MongoDB/config (Sprint 2, P3)

---

### Q64. «Как принимались архитектурные решения?»

**Процесс:**
1. **Proposal:** Разработчик (или Tech Lead) пишет proposal: problem, options (2-3 альтернативы), trade-offs, recommendation.
2. **Design Review:** 30-минутная встреча с командой. Обсуждение trade-offs, вопросы, concerns.
3. **Decision:** Tech Lead (Daniel) принимает окончательное решение. Если консенсус — ОК. Если нет — Daniel has final say (disagree and commit).
4. **ADR (Architecture Decision Record):** Решение документируется: Context, Options, Decision, Consequences. 12+ ADR за проект.

**Пример — ADR-007 (Ktor для Pricing):**
- Context: Pricing — stateless, compute-heavy, gRPC
- Options: Spring Boot (consistency), Ktor (lightweight, coroutines), Micronaut (compile-time DI)
- Decision: Ktor — lightweight, native coroutines, DSL routing, ~40MB image
- Consequences: Меньше ecosystem, нет Spring Data, отдельный learning curve

**Принцип:** Reversible decisions — быстро, один approve. Irreversible (new database, new language) — дольше, 2 approvals, ADR обязательно.

---

### Q65. «Какой был workflow задачи от бэклога до production?»

**7 шагов:**
1. **Backlog:** Product Owner (Alex) приоритизирует. Story с acceptance criteria.
2. **Sprint Planning:** Команда берёт задачи, оценивает (Fibonacci), определяет Sprint Goal.
3. **In Progress:** Разработчик создаёт feature branch от main. Пишет код + тесты + миграции.
4. **Code Review:** PR в main. CI запускается автоматически. Reviewer назначается. Правило 4 часов.
5. **QA:** Alex проверяет на staging (docker compose с production-like данными). Acceptance criteria validated.
6. **Merge:** PR merge в main. CI повторно (main branch). Docker image tagged с git SHA.
7. **Deploy:** Helm upgrade на staging → smoke tests → production (rolling update, zero-downtime).

**Cycle time:** От начала работы до merge — 1-3 дня. От merge до production — часы (CD pipeline).

**Rollback:** `helm rollback` — предыдущая версия за 30 секунд. Kafka consumer lag может вырасти при rollback consumer-breaking change — мониторим.

---

### Q66. «Как устроен on-call?»

**Ротация:** 4 человека (я, два backend-разработчика, DevOps Maria). 1 неделя on-call, потом 3 недели off. Handoff — пятница, 30-минутный sync: текущие issues, watch items.

**Severity:**
- **P1 (Critical):** Система недоступна или теряет данные. Response: 15 min. Пример: OOM kill Transfer Service, Kafka cluster down.
- **P2 (High):** Деградация, но работает. Response: 1 hour. Пример: High error rate, circuit breaker open.
- **P3 (Medium):** Не влияет на пользователей. Response: next business day. Пример: DLT messages, high consumer lag.

**Alerting Pipeline:** Prometheus → Alertmanager → routing: P1 → PagerDuty (phone call), P2 → Slack #alerts channel, P3 → Slack #monitoring.

**Runbooks:** Для каждого alert — runbook: что проверить, как mitigation, escalation path. Пример: `HighMemoryUsage` → check heap dump → identify leak → restart Pod (short-term) → fix code (long-term).

**Post-mortem:** После каждого P1 — blameless post-mortem: timeline, root cause, action items. Документ в shared drive. Action items добавляются в tech debt backlog.
