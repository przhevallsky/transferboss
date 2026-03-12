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
