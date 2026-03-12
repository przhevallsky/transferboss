# Sprint 7 — Polish & Interview Prep: Декомпозиция на блоки

## Sprint Goal

Проект полностью задокументирован и готов к презентации. Файл interview-qa.md содержит 80+ вопросов с развёрнутыми ответами. Архитектурные диаграммы Level 2–4 отрисованы. README позволяет запустить систему с нуля. CV bullet points сформулированы.

**Что это даёт:** Milestone M7: Interview Ready. Технический опыт превращён в умение уверенно о нём рассказывать. Любой вопрос на собеседовании — конкретный, аргументированный ответ с примерами из проекта.

**Длительность:** 1 неделя (сокращённый спринт). ~18 SP.

---

## Обзор блоков

| Block | Содержание | Tasks | Зависимости |
|-------|-----------|-------|-------------|
| **B1** | Interview Q&A: Architecture + System Design (20+ вопросов) | S7-T01 (часть 1) | — |
| **B2** | Interview Q&A: Kafka + DB + Patterns (20+ вопросов) | S7-T01 (часть 2) | — |
| **B3** | Interview Q&A: Infra + Observability + Security + Process (20+ вопросов) | S7-T01 (часть 3) | — |
| **B4** | Interview Q&A: LLM + ClickHouse + Evolution + Behavioral (20+ вопросов) | S7-T01 (часть 4) | — |
| **B5** | Диаграммы Level 2: internal structure Transfer Service + Pricing Service | S7-T02 | — |
| **B6** | Диаграммы Level 3: flow diagrams — create transfer, saga, retry | S7-T03 | — |
| **B7** | Диаграммы Level 4 + «Было/Стало» | S7-T04, S7-T05 | B5, B6 |
| **B8** | README.md + CV bullet points | S7-T06, S7-T07 | — |
| **B9** | ADR review + финальное демо сценарий | S7-T08, S7-T09 | — |
| **B10** | Tech Debt: Code cleanup + JaCoCo coverage report в CI | S7-T10, S7-T11 | — |

---

## Зависимости между блоками

```
Interview Q&A (параллельно, 4 блока):
B1 (Architecture)  }
B2 (Kafka + DB)    } — независимы друг от друга, можно в любом порядке
B3 (Infra + Ops)   }
B4 (LLM + Behavioral) }

Диаграммы:
B5 (Level 2) ──┐
B6 (Level 3) ──┴──→ B7 (Level 4 + Было/Стало)

Документация:
B8 (README + CV) — независим
B9 (ADR review + Demo) — независим

Tech Debt:
B10 (Cleanup + JaCoCo) — независим
```

Большинство блоков независимы — это спринт документации, не кода. Рекомендуемый подход: чередовать Q&A-блоки с диаграммами, чтобы не выгорать на одном типе работы.

---

## Детали каждого блока

### Block 1 — Interview Q&A: Architecture + System Design

**Файл:** `docs/interview-qa.md` (секции 1–2)

**Контекст:** Самый ценный артефакт проекта для собеседований. Не теоретические ответы, а конкретные истории из проекта в формате STAR (Situation → Task → Action → Result). Каждый ответ — 5–15 предложений, как на реальном собеседовании.

**Что делать:**

*Секция 1: Общие вопросы о проекте (8–10 вопросов):*

Для каждого вопроса — развёрнутый ответ, привязанный к TransferHub:

1. **«Расскажите о проекте, над которым вы работали»**
   - Elevator pitch (2 предложения) + развёрнутое описание
   - Покрыть: что за система, бизнес-проблема, масштаб, моя роль
   - Формат: «TransferHub — cross-border remittance platform, обрабатывающая тысячи переводов в сутки. Я отвечал за Transfer Service и Pricing Service — два ключевых сервиса, через которые проходит каждый перевод...»

2. **«Какова архитектура системы? Нарисуйте high-level схему»**
   - Описание: event-driven microservices, Kafka как backbone, синхронные REST/gRPC только для blocking checks
   - Перечислить: 6+ сервисов, их ответственности, хранилища
   - Обосновать: почему микросервисы (проблемы монолита), почему event-driven (decoupling, replay, multi-consumer)

3. **«Сколько человек было в команде? Какая у вас была роль?»**
   - 7 человек, перечислить роли, описать свою подробно
   - Ownership над Transfer Service и Pricing Service end-to-end

4. **«Какой технологический стек и почему?»**
   - Kotlin (null safety, coroutines), Spring Boot + Ktor (сравнение), Go (Notification Gateway), PostgreSQL + MongoDB + Redis (polyglot persistence), Kafka, K8s, Terraform

5. **«Какой самый сложный технический вызов?»**
   - STAR: Redirect & Retry Topic для сохранения ordering нотификаций
   - Или: расследование утечки памяти
   - Или: проектирование Saga с компенсациями

6. **«Расскажите про случай, когда пришлось кардинально изменить решение»**
   - STAR: @RetryableTopic → Redirect & Retry (Sprint 3 → Sprint 4)
   - Почему: @RetryableTopic не сохраняет ordering, что критично для нотификаций
   - Как: TDD, design review с Notifications-командой, согласование на sync-встрече

7. **«Как вы обеспечиваете качество кода?»**
   - Code review (правило 4 часов), CI pipeline (lint + test + build), integration tests (Testcontainers), Definition of Done

8. **«Что бы вы сделали иначе, если бы начинали проект заново?»**
   - Schema Registry с первого дня (а не JSON в Kafka)
   - Contract tests вместо mock-сервисов для Payment/Payout
   - Сразу Caffeine для in-memory кэшей (а не ConcurrentHashMap)

*Секция 2: Architecture & System Design (10–12 вопросов):*

9. **«Почему микросервисы, а не монолит?»**
10. **«Как сервисы взаимодействуют? Почему Kafka, а не REST?»**
11. **«Как обеспечиваете консистентность между сервисами?»**
12. **«Что такое Outbox Pattern и зачем?»**
13. **«Как реализовали Saga? Choreography или Orchestration?»**
14. **«Что будет, если один из сервисов упадёт?»**
15. **«Почему PostgreSQL для transfers, а MongoDB для corridor configs?»**
16. **«Как спроектирована схема данных?»**
17. **«Почему Ktor для Pricing, а не Spring Boot?»**
18. **«Зачем gRPC между Transfer и Pricing?»**
19. **«Как устроен distributed locking через Consul?»**
20. **«Как вы решаете проблему dual write (БД + Kafka)?»**

Каждый ответ: конкретный пример из TransferHub, альтернативы, trade-offs, цифры где возможно.

**Результат:** 20+ вопросов с развёрнутыми ответами по архитектуре и system design.

---

### Block 2 — Interview Q&A: Kafka + Databases + Patterns

**Файл:** `docs/interview-qa.md` (секции 3–5)

**Что делать:**

*Секция 3: Kafka и обмен сообщениями (10–12 вопросов):*

21. **«Как гарантируете порядок сообщений в Kafka?»**
    - key = transfer_id → одна партиция → ordering гарантирован
    - Outbox Service группирует по transfer_id перед отправкой

22. **«Расскажите про Redirect & Retry Topic»**
    - STAR: проблема ordering при retry → redirect set → retry consumer → sequential processing

23. **«Что если consumer не может обработать сообщение?»**
    - Path: retry с backoff → после N попыток → DLT → алерт → ручной разбор

24. **«Как обеспечиваете idempotent consumer?»**
    - processed_events table, проверка event_id перед обработкой, одна транзакция с бизнес-данными

25. **«Какие гарантии доставки (acks) и почему?»**
    - acks=all для финансовых топиков (durability), цифры: +5ms latency vs потеря данных

26. **«Как мониторите Kafka?»**
    - Consumer lag (ключевая метрика), Kafka Exporter → Prometheus → Grafana, алерт при lag > 10K

27. **«Что такое @RetryableTopic и когда его НЕ стоит использовать?»**
    - Не сохраняет ordering → не подходит для нотификаций

28. **«Чем отличается micrometerEnabled от observationEnabled в Spring Kafka?»**
29. **«Как работает CooperativeStickyAssignor?»**
30. **«Что будет, если Kafka полностью недоступна?»**

*Секция 4: Базы данных и производительность (8–10 вопросов):*

31. **«Расскажите про оптимизацию медленного SQL-запроса»**
    - STAR: cursor-based pagination — OFFSET замедлялся при глубоком пейджинге, перешли на WHERE id > cursor

32. **«Как работает EXPLAIN ANALYZE?»**
33. **«Зачем SELECT FOR UPDATE SKIP LOCKED?»** — Outbox polling
34. **«Как делали миграцию MongoDB на живой системе?»** — distributed lock, batch, dry-run
35. **«Как устроено кэширование? Как решали cache invalidation?»**
    - Cache-Aside, TTL-based + event-driven, cache stampede protection
36. **«Расскажите про утечку памяти»** — полный STAR с метриками before/after
37. **«Что такое idempotency key и как реализовали?»**
38. **«Какие уровни изоляции транзакций использовали?»**
39. **«Как предотвращаете deadlock'и?»** — Consul distributed lock, не PostgreSQL SELECT FOR UPDATE

*Секция 5: Конкурентность и защита от дублирования (4–5 вопросов):*

40. **«Как защищаетесь от дабл-клика?»**
41. **«Optimistic vs Pessimistic locking — когда что?»**
42. **«Как обеспечивается атомарность проверки idempotency key?»**
43. **«Как несколько инстансов Outbox Service работают одновременно?»**

**Результат:** 20+ вопросов по Kafka, DB, concurrency с конкретными примерами из TransferHub.

---

### Block 3 — Interview Q&A: Infrastructure + Observability + Security + Process

**Файл:** `docs/interview-qa.md` (секции 6–9)

**Что делать:**

*Секция 6: Docker и Kubernetes (8–10 вопросов):*

44. **«Как устроен ваш Dockerfile?»** — multi-stage, non-root, layer caching
45. **«Чем liveness probe отличается от readiness?»** — примеры неправильной настройки
46. **«Как обеспечивается zero-downtime деплой?»** — rolling update + readiness + preStop hook
47. **«Как рассчитываете resource requests/limits для JVM?»** — Xmx + metaspace + overhead, OOMKilled story
48. **«Как работает HPA?»** — CPU + custom metrics (consumer lag), ограничение: partitions = max consumers
49. **«Что такое Helm и зачем?»** — шаблонизация, values per environment
50. **«Как управляете конфигурацией и секретами?»** — ConfigMap + External Secrets + Vault

*Секция 7: Observability (6–8 вопросов):*

51. **«Какой observability стек?»** — Prometheus + Grafana + Loki + Tempo, единый UI
52. **«Как от алерта добраться до root cause?»** — полный workflow: метрика → exemplar → трейс → логи
53. **«Почему Loki, а не ELK?»** — масштаб, стоимость, единый UI; когда ELK лучше
54. **«Как distributed tracing через Kafka?»** — W3C Trace Context в headers, observation-enabled
55. **«Какие алерты настроены?»** — 8+ правил с routing P1/P2
56. **«Какие дашборды? Какие метрики ключевые?»** — RED, business metrics, Kafka lag

*Секция 8: Security (4–5 вопросов):*

57. **«Как защищён API?»** — JWT (RS256), RBAC (SENDER/OPERATOR), rate limiting
58. **«Как работает rate limiting?»** — Redis sliding window, 100 req/min, 429 + Retry-After
59. **«Как маскируете PII в логах?»** — custom Logback encoder, автоматическое маскирование
60. **«Как управляете секретами?»** — Vault → External Secrets Operator → K8s Secrets

*Секция 9: Процессы и команда (8–10 вопросов):*

61. **«Как была организована работа?»** — Scrum, 2 недели, Planning/Daily/Review/Retro
62. **«Как проходил Sprint Planning?»** — формат, оценка (Fibonacci), velocity ~25 SP
63. **«Как проходил code review?»** — правило 4 часов, на что смотрят, сколько approve
64. **«Расскажите про ретроспективу и улучшение»** — STAR: integration tests в DoD
65. **«Как взаимодействовали с другими командами?»** — Payments, Payouts, Identity контракты
66. **«Как решали конфликты при изменении API?»** — RFC, sync-встреча, переходный период
67. **«Как работали с техническим долгом?»** — отдельный бэклог, 15-20% спринта
68. **«Как принимались архитектурные решения?»** — TDD → design review → Daniel approve → ADR
69. **«Какой был workflow задачи от бэклога до prod?»** — To Do → In Progress → Review → QA → Deploy
70. **«Как устроен on-call?»** — 4 человека, ротация 1 неделя, P1/P2/P3 severity

**Результат:** 25+ вопросов по инфраструктуре, observability, security и процессам.

---

### Block 4 — Interview Q&A: LLM + ClickHouse + Evolution + Behavioral

**Файл:** `docs/interview-qa.md` (секции 10–13)

**Что делать:**

*Секция 10: LLM-инженерия (6–8 вопросов):*

71. **«Расскажите про RAG pipeline»** — pgvector, chunking с overlap, similarity search
72. **«Почему pgvector, а не отдельная векторная БД?»** — PostgreSQL уже в стеке, масштаб не требует
73. **«Как оцениваете качество ответов LLM?»** — relevance, faithfulness, confidence score
74. **«Как устроена отказоустойчивость при вызове LLM API?»** — circuit breaker, fallback
75. **«Как решали проблему галлюцинаций?»** — RAG (ground truth), system prompt, confidence threshold
76. **«Как реализовали streaming ответов?»** — SSE, Flow → Flux, token-by-token
77. **«Зачем мониторить token usage?»** — cost control, $5/M tokens adds up

*Секция 11: ClickHouse и аналитика (4–5 вопросов):*

78. **«Зачем ClickHouse, если есть PostgreSQL?»** — OLTP vs OLAP, 200ms vs 30 sec для агрегаций
79. **«Как данные попадают в ClickHouse?»** — Kafka ETL consumer, batch insert каждые 30 сек
80. **«Что такое ReplacingMergeTree?»** — дедупликация при replay
81. **«Что такое LowCardinality?»** — dictionary encoding, 10x сжатие

*Секция 12: Terraform и IaC (3–4 вопроса):*

82. **«Как описана инфраструктура?»** — Terraform, модульная структура, VPC/EKS/RDS
83. **«Как управляете Terraform state?»** — S3 + DynamoDB locking
84. **«Что будет, если руками изменить ресурс в AWS Console?»** — drift detection при plan

*Секция 13: Feature Flags + Evolution + Behavioral (6–8 вопросов):*

85. **«Как деплоите незавершённые фичи?»** — Unleash feature flags, trunk-based development
86. **«Как делаете canary release?»** — Gradual Rollout 5% → 25% → 100%, мониторинг
87. **«Расскажите про эволюцию решения»** — @RetryableTopic → Redirect & Retry (STAR)
88. **«Как вы справлялись с конфликтующими приоритетами?»** — бизнес vs tech debt, Alex как арбитр
89. **«Расскажите про ситуацию, когда Sprint Goal не был достигнут»** — реалистичная история, action items
90. **«Что было самым неожиданным за время проекта?»** — утечка памяти / payout-партнёр DUPLICATE_REFERENCE

**Результат:** 20+ вопросов по LLM, analytics, IaC, feature flags и behavioral. Всего 90+ вопросов в файле.

---

### Block 5 — Архитектурные диаграммы Level 2

**Файл:** `docs/diagrams/` (draw.io или Mermaid)

**Контекст:** Level 1 (high-level) создана в Sprint 0. Level 2 — детальная внутренняя структура ключевых сервисов. На собеседовании: «Вот внутренняя архитектура Transfer Service» — и рисуешь слои, модули, потоки данных.

**Что делать:**

*Level 2: Transfer Service internal structure:*
- Показать слои: REST Controller → Service Layer → Repository (PostgreSQL) + Outbox
- Kafka consumers: payment events, payout events, identity events
- Kafka producer: через Outbox Service (не напрямую)
- Redis: idempotency cache, SSE pub/sub
- gRPC client → Pricing Service
- REST client → Identity Service (с circuit breaker)
- State machine: sealed class TransferStatus с переходами
- WebFlux SSE endpoint (отдельно от MVC)
- Обозначить: Resilience4j circuit breakers на внешних вызовах
- Обозначить: Unleash feature flag check

*Level 2: Pricing Service internal structure:*
- gRPC Server (Ktor)
- REST endpoint (для BFF/Client Apps)
- Calculation pipeline: fee calculator (legacy/tiered через feature flag) + exchange rate + receive amount
- Redis: exchange rate cache (TTL 30s), rate lock (quote_id → rate, TTL 30s), corridor config cache
- MongoDB: corridor configs (source of truth)
- Обозначить: Ktor DSL routing, coroutines
- Обозначить: Caffeine L1 cache перед Redis

*Формат:* draw.io (`.drawio` файлы) или Mermaid (`.mermaid` файлы, рендерятся в GitLab/GitHub). Draw.io предпочтительнее для визуальной детализации.

*Стиль:*
- Цветовая кодировка: зелёный = наш код, синий = внешние вызовы, оранжевый = Kafka, серый = хранилища
- Подписи на стрелках: «REST», «gRPC», «Kafka: transfers.payment.requested», «Redis GET/SET»
- Паттерны подписаны: «Circuit Breaker», «Outbox Pattern», «Cache-Aside»

**Результат:** 2 детальные диаграммы внутренней структуры Transfer Service и Pricing Service. Можно нарисовать на whiteboard за 3–5 минут.

---

### Block 6 — Архитектурные диаграммы Level 3: Flow Diagrams

**Файл:** `docs/diagrams/`

**Контекст:** Level 3 — пошаговые сценарии. Sequence diagrams или flow charts, показывающие конкретный бизнес-сценарий step-by-step. На собеседовании: «Покажите, что происходит при создании перевода».

**Что делать:**

*Flow 1: Create Transfer — Happy Path (sequence diagram):*
```
Client → Transfer Service: POST /api/v1/transfers
Transfer Service → Redis: check idempotency key
Transfer Service → Identity Service: GET /kyc-status [circuit breaker]
Transfer Service → Pricing Service: gRPC ValidateQuote [circuit breaker]
Transfer Service → PostgreSQL: BEGIN
    INSERT transfers (status=CREATED)
    INSERT outbox_events (transfer.payment.requested)
    INSERT processed_requests (idempotency_key)
COMMIT
Transfer Service → Redis: SET idempotency key (TTL 24h)
Transfer Service → Redis: PUBLISH transfer-status:txn_123 (status=CREATED)
Transfer Service → Client: 201 Created

[Async]
Outbox Service → PostgreSQL: SELECT FOR UPDATE SKIP LOCKED
Outbox Service → Kafka: publish transfer.payment.requested (key=transfer_id)
Outbox Service → PostgreSQL: UPDATE status=SENT

Mock Payment → Kafka: publish payment.captured
Transfer Service consumer → PostgreSQL: UPDATE status=PAYMENT_CAPTURED
Transfer Service → outbox: INSERT transfer.payout.requested
...
Mock Payout → Kafka: publish payout.completed
Transfer Service consumer → PostgreSQL: UPDATE status=COMPLETED
Transfer Service → Redis: PUBLISH transfer-status:txn_123 (status=COMPLETED)
```

*Flow 2: Saga Compensation — Payout Failed (sequence diagram):*
```
Mock Payout → Kafka: publish payout.failed (reason: INVALID_ACCOUNT)
Transfer Service consumer:
    UPDATE status: PAYMENT_CAPTURED → PAYOUT_FAILED
    INSERT outbox: transfer.payment.refund.requested
    PUBLISH Redis: status=PAYOUT_FAILED

Outbox → Kafka: transfer.payment.refund.requested
Mock Payment → Kafka: payment.refunded
Transfer Service consumer:
    UPDATE status: PAYOUT_FAILED → REFUNDED
    INSERT outbox: transfer.status_changed (REFUNDED)
    PUBLISH Redis: status=REFUNDED
```

*Flow 3: Redirect & Retry — Ordering Preserved:*
```
Main Consumer receives Event A (transfer_123, PAYMENT_CAPTURED):
    → delivery fails
    → ADD transfer_123 to redirect set
    → SEND Event A to retry topic

Main Consumer receives Event B (transfer_123, COMPLETED):
    → CHECK redirect set: transfer_123 exists
    → REDIRECT Event B to retry topic (NOT delivered directly)

Retry Consumer:
    → Process Event A → success
    → Process Event B → success
    → CLEAR redirect for transfer_123

Result: User receives notifications in correct order: A before B
```

*Flow 4: Circuit Breaker state transitions:*
```
CLOSED → [5 failures / 10 calls = 50%] → OPEN → [wait 30s] → HALF_OPEN → [3 test calls]
    ↑                                                                          |
    └────────── [2/3 successful] ──────────────────────────────────────────────┘
                                    [2/3 failed] → OPEN (back)
```

*Формат:* draw.io для sequence diagrams, или Mermaid sequenceDiagram. Mermaid проще для text-based, draw.io красивее.

**Результат:** 4 flow diagrams покрывают: happy path, saga compensation, redirect & retry, circuit breaker. Ключевые сценарии, которые спрашивают на собеседованиях.

---

### Block 7 — Диаграммы Level 4 + «Было/Стало»

**Файл:** `docs/diagrams/`

**Контекст:** Level 4 — инфраструктурная схема (как всё задеплоено). «Было/Стало» — самый мощный инструмент для собеседования: показывает эволюцию системы и обосновывает каждое усложнение.

**Что делать:**

*Level 4: Kubernetes Deployment Diagram:*
- Показать:
  - Ingress Controller (nginx) → Transfer Service (2–8 Pod'ов, HPA)
  - Transfer Service Pods → PostgreSQL (RDS)
  - Transfer Service Pods → Redis (ElastiCache)
  - Transfer Service Pods → Kafka (MSK)
  - Pricing Service (2–12 Pod'ов, HPA) — gRPC port + HTTP port
  - Outbox Service (2–4 Pod'а) → PostgreSQL → Kafka
  - Notification Gateway Go (2–16 Pod'ов, HPA) → Kafka
  - LLM Service (2 Pod'а) → PostgreSQL (pgvector) → OpenAI API
  - Analytics ETL (1–2 Pod'а) → Kafka → ClickHouse
  - Consul (3 Pod'а StatefulSet)
  - Unleash (1 Pod) → PostgreSQL
  - Monitoring namespace: Prometheus, Grafana, Loki, Tempo, Alertmanager
- Annotations на каждом Deployment: resource requests, HPA range, probes
- Namespaces: `transferhub` (application), `monitoring` (observability), `infra` (Consul, Unleash)
- Network: ClusterIP services, один Ingress для external traffic

*«Было» — начальная архитектура (Sprint 0–1):*
- Простая диаграмма:
  ```
  Client → Transfer Service → PostgreSQL
                ↓
           Pricing Service → Redis
  ```
- Нет Kafka, нет Outbox, нет Saga, нет retry, нет мониторинга
- Прямая запись в БД, синхронные вызовы, никакой отказоустойчивости
- Подпись: «MVP: минимальная работающая система, one happy path»

*«Стало» — текущая архитектура (Sprint 7):*
- Полная диаграмма со всеми паттернами:
  - Outbox Pattern (гарантированная доставка)
  - Saga с компенсациями (полный lifecycle)
  - Redirect & Retry (ordering preservation)
  - Circuit Breaker на внешних вызовах
  - SSE через Redis Pub/Sub
  - Feature Flags (Unleash)
  - Distributed Locking (Consul)
  - Full Observability (Prometheus + Grafana + Loki + Tempo)
  - JWT + RBAC + Rate Limiting
  - RAG/LLM Service
  - ClickHouse Analytics ETL
- Подпись: «Production-ready: отказоустойчивость, observability, безопасность»

*Между «Было» и «Стало» — аннотации с причинами каждого усложнения:*
- «Добавили Outbox Pattern, потому что прямая отправка в Kafka из транзакции → риск потери при сбое между commit и send»
- «Добавили Circuit Breaker, потому что timeout к Pricing зависал и исчерпывал thread pool»
- «Добавили Redirect & Retry, потому что @RetryableTopic нарушал ordering нотификаций»
- «Добавили Consul, потому что SELECT FOR UPDATE не масштабируется при 6+ репликах»

*На собеседовании:* «Вот как система выглядела в начале — простой REST + PostgreSQL. А вот как выглядит сейчас. Каждое усложнение обосновано конкретной проблемой. Например, Outbox Pattern появился, когда мы обнаружили, что при crash между commit в БД и отправкой в Kafka — событие терялось. Это стоило нам потерянного перевода на staging.»

**Результат:** Level 4 infrastructure diagram + «Было/Стало» с аннотациями. Самый мощный визуальный артефакт для собеседований.

---

### Block 8 — README.md + CV Bullet Points

**Файлы:** `README.md` (корень репозитория), `docs/cv-bullets.md`

**Контекст:** README — «входная дверь» проекта. Интервьюер клонирует репозиторий — README должен за 2 минуты дать полную картину и позволить запустить систему. CV bullets — готовые формулировки для резюме.

**Что делать:**

*README.md — структура:*

```markdown
# TransferHub — Cross-Border Remittance Platform

> Event-driven microservices platform for international money transfers.
> Kotlin · Spring Boot · Ktor · Go · Kafka · PostgreSQL · MongoDB · Redis · ClickHouse · Kubernetes

## Architecture Overview
[Level 1 диаграмма — inline или ссылка]

## Tech Stack
[Таблица: категория → технология → зачем]

## Services
| Service | Language | Framework | Purpose |
|---------|----------|-----------|---------|
| Transfer Service | Kotlin | Spring Boot | Core transfer lifecycle, REST API, Saga orchestration |
| Pricing Service | Kotlin | Ktor | Quote calculation, gRPC, exchange rates |
| Outbox Service | Kotlin | Spring Boot | Guaranteed event delivery (Outbox Pattern) |
| Notification Gateway | Go | stdlib + kafka-go | Push/SMS delivery, Prometheus metrics |
| LLM Service | Kotlin | Spring Boot | RAG-based AI assistant, pgvector, SSE |
| Analytics ETL | Kotlin | Spring Boot | Kafka → ClickHouse batch ETL |

## Key Patterns & Practices
- **Outbox Pattern** — transactional event publishing
- **Choreography-based Saga** — with compensation (refund)
- **Redirect & Retry Topic** — ordering-preserving error handling
- **Circuit Breaker** (Resilience4j) — graceful degradation
- **Feature Flags** (Unleash) — safe rollout
- **RAG Pipeline** — pgvector + OpenAI for support AI
- **CQRS-light** — PostgreSQL (writes) + ClickHouse (analytics)

## Quick Start
```bash
# Clone
git clone ...

# Start all infrastructure
docker compose up -d

# Start with monitoring (Prometheus, Grafana, Loki, Tempo)
docker compose --profile monitoring up -d

# Access
- Transfer Service API: http://localhost:8080/swagger-ui
- Pricing Service API: http://localhost:8081/swagger-ui
- Grafana: http://localhost:3000 (admin/admin)
- Unleash: http://localhost:4242 (admin/unleash4all)
- ClickHouse: http://localhost:8123
```

## Documentation
- [Architecture](/docs/system-architecture.md)
- [API Contracts](/docs/api-contracts.md)
- [ADRs](/docs/adr/)
- [Data Schema](/docs/data-schema.md)
- [Runbooks](/docs/runbooks/)

## Project Structure
[Дерево директорий]
```

*CV Bullet Points (`docs/cv-bullets.md`):*

```markdown
# CV Bullet Points — TransferHub Platform

## Summary
Designed and implemented event-driven microservices platform for cross-border remittances 
processing 1000+ daily transfers across 10+ corridors.

## Architecture & Design
- Designed event-driven microservices architecture with Kafka as central backbone, 
  handling 100+ events/sec with guaranteed delivery via Outbox Pattern
- Implemented choreography-based Saga pattern with compensation flows 
  (payment → compliance → payout → completion/refund)
- Architected polyglot persistence: PostgreSQL (ACID transactions), 
  MongoDB (flexible configs), Redis (sub-ms caching), ClickHouse (OLAP analytics)

## Backend Development
- Developed Transfer Service (Kotlin/Spring Boot) and Pricing Service (Kotlin/Ktor) — 
  core services handling every transfer in the system
- Built gRPC inter-service communication achieving 3x latency reduction vs REST/JSON 
  (5ms vs 15ms per call at 200 RPS)
- Implemented SSE real-time status updates via Spring WebFlux + Redis Pub/Sub

## Resilience & Reliability
- Designed Redirect & Retry Topic pattern preserving notification ordering during failures
- Implemented Circuit Breaker (Resilience4j) with differentiated fallback strategies: 
  cached data for Pricing, fast-fail for compliance checks
- Achieved idempotent processing across REST API (X-Idempotency-Key) and 
  Kafka consumers (processed_events table)

## Observability & Operations
- Built full observability stack: Prometheus + Grafana + Loki + Tempo with 
  end-to-end distributed tracing through REST and Kafka
- Investigated and resolved memory leak: identified unbounded cache via heap dump analysis 
  (Eclipse MAT), replaced with Caffeine (maxSize + TTL), stabilized heap at baseline
- Configured 8+ alert rules with Alertmanager routing (P1→PagerDuty, P2→Slack)

## Infrastructure & DevOps
- Containerized all services with Docker (multi-stage builds, 15MB Go / 200MB JVM images)
- Created Helm charts for Kubernetes deployment with rolling updates, 
  health probes, and HPA auto-scaling
- Described AWS infrastructure via Terraform (VPC, EKS, RDS, S3)
- Configured GitLab CI/CD pipeline: lint → test → build → deploy with 
  environment-specific configurations

## Security
- Implemented JWT authentication (RS256) with RBAC: SENDER/OPERATOR/ADMIN roles
- Built rate limiting via Redis sliding window (100 req/min per user)
- Automated PII masking in logs via custom Logback encoder

## AI/ML Integration
- Implemented RAG pipeline: document chunking → OpenAI embeddings → pgvector 
  similarity search → LLM response with SSE streaming
- Built circuit breaker on LLM API with fallback, monitoring token usage for cost control

## Analytics
- Designed ClickHouse analytics pipeline: Kafka ETL → batch insert → 
  ReplacingMergeTree with LowCardinality optimization
- Built Grafana analytics dashboard: transfer volume, revenue by corridor, success rate
```

**Результат:** README позволяет запустить проект за 2 минуты. CV bullets — готовые формулировки для каждого аспекта проекта.

---

### Block 9 — ADR Review + Финальное демо сценарий

**Файлы:** `docs/adr/`, `docs/demo-scenario.md`

**Контекст:** ADR накопились за 7 спринтов. Нужно убедиться, что все актуальны, ссылки корректны, нет пропущенных решений. Демо-сценарий — пошаговый walkthrough для презентации системы.

**Что делать:**

*ADR Review:*
- Проверить все ADR (ожидается 12–15+):
  - ADR-001: Architecture Style (microservices)
  - ADR-002: Primary Language (Kotlin)
  - ADR-003: Message Broker (Kafka)
  - ADR-004: Go for Notification Gateway
  - ADR-005: PostgreSQL
  - ADR-006: MongoDB
  - ADR-007: Ktor for Pricing Service
  - ADR-008: Redis Caching
  - ADR-009: GitLab CI/CD
  - ADR-010: Terraform
  - ADR-011: Observability Stack
  - + ADR для: Consul, Circuit Breaker strategy, Redirect & Retry, Unleash, ClickHouse, RAG/pgvector
- Для каждого ADR проверить:
  - Статус актуален (Accepted / Deprecated)
  - Контекст соответствует реальности проекта (не устарел)
  - Альтернативы описаны честно
  - Последствия отражают реальный опыт (добавить, если по ходу проекта обнаружились нюансы)
  - Ссылки на другие документы корректны
- Добавить недостающие ADR, если есть решения без документации

*Финальное демо — сценарий (`docs/demo-scenario.md`):*

```markdown
# TransferHub — End-to-End Demo Scenario

## Preparation
1. `docker compose --profile monitoring up -d`
2. Open tabs: Swagger UI, Grafana, Unleash, ClickHouse client

## Act 1: Create Transfer (Happy Path)
1. Get JWT token: POST /auth/token (userId: usr_demo, roles: [SENDER])
2. Get quote: POST /api/v1/quotes (US→PH, $500, BANK_DEPOSIT)
3. Create transfer: POST /api/v1/transfers (with quote_id, idempotency key)
4. Show: 201 Created, status=CREATED
5. Show: outbox_events table — event pending
6. Wait 1 sec — show: event status=SENT, Kafka message in topic

## Act 2: Saga Lifecycle
7. Mock Payment publishes payment.captured → show status=PAYMENT_CAPTURED
8. Mock Payout publishes payout.completed → show status=COMPLETED
9. Show: SSE endpoint received all status updates in real-time

## Act 3: Failure + Compensation
10. Create new transfer → payment.captured → payout.failed
11. Show: status=PAYOUT_FAILED → refund.requested → payment.refunded → REFUNDED
12. Show: notification events in correct order (Redirect & Retry)

## Act 4: Resilience
13. Stop Pricing Service → create transfer → Circuit Breaker OPEN → 503
14. Show: Grafana — circuit breaker state metric = OPEN
15. Restart Pricing → circuit closes → transfers work again

## Act 5: Observability
16. Show: Grafana Transfer Service dashboard (RED metrics)
17. Click exemplar → Tempo trace (REST → gRPC → PostgreSQL)
18. Click span → Loki logs (filtered by traceId)
19. Show: alert rules, Kafka consumer lag dashboard

## Act 6: Feature Flags
20. Unleash UI: toggle new-pricing-algorithm ON for 50%
21. Create two transfers → show different fee calculations

## Act 7: AI Assistant
22. POST /api/v1/assistant/ask — "What are transfer limits to Philippines?"
23. Show: RAG response with sources and confidence

## Act 8: Analytics
24. Open Grafana ClickHouse dashboard
25. Show: transfer volume by corridor, success rate, revenue
```

**Результат:** Все ADR актуальны и полные. Демо-сценарий позволяет за 15–20 минут продемонстрировать все аспекты системы.

---

### Block 10 — Tech Debt: Code Cleanup + JaCoCo Coverage

**Все сервисы**

**Контекст:** Финальный cleanup перед «замораживанием» проекта. Удаление TODO, мёртвого кода, финализация комментариев. JaCoCo coverage report в CI — чтобы в README можно было указать «test coverage > 70%».

**Что делать:**

*Code Cleanup:*
- Поиск всех TODO/FIXME/HACK в коде:
  ```bash
  grep -r "TODO\|FIXME\|HACK\|XXX" services/ --include="*.kt" --include="*.go" --include="*.java"
  ```
- Для каждого TODO: либо исправить, либо оформить как tech debt ticket в бэклоге, либо удалить если неактуально
- Удалить мёртвый код: неиспользуемые классы, методы, imports
- Финализировать комментарии: заменить placeholder-комментарии на информативные
- Убедиться, что все `// INTENTIONAL MEMORY LEAK` из Sprint 6 B8 удалены (утечка уже исправлена в B9)
- Проверить `.gitignore`: нет ли случайно закоммиченных `.env`, `.idea`, `*.hprof`
- Проверить docker-compose.yml: нет ли хардкоженных секретов (даже для dev — лучше через .env)

*JaCoCo Coverage Report в GitLab CI:*
- Добавить JaCoCo plugin в `build.gradle.kts` каждого JVM-сервиса:
  ```kotlin
  plugins {
      jacoco
  }
  
  tasks.jacocoTestReport {
      reports {
          xml.required.set(true)   // для GitLab CI
          html.required.set(true)  // для локального просмотра
      }
  }
  
  tasks.test {
      finalizedBy(tasks.jacocoTestReport)
  }
  ```
- В `.gitlab-ci.yml` — добавить artifacts и coverage regex:
  ```yaml
  test:transfer-service:
    script:
      - cd services/transfer-service
      - ./gradlew test jacocoTestReport
    artifacts:
      reports:
        junit: services/transfer-service/build/test-results/test/*.xml
        coverage_report:
          coverage_format: cobertura
          path: services/transfer-service/build/reports/jacoco/test/jacocoTestReport.xml
    coverage: '/Total.*?([0-9]{1,3})%/'
  ```
- GitLab отображает coverage в MR badge и в pipeline
- Target: > 70% для каждого сервиса

*Go Coverage:*
- Уже настроен в Sprint 3 (B7): `go test -cover`
- Убедиться, что coverage report генерируется в CI и отображается

*Финальная проверка:*
- `docker compose up` → все сервисы стартуют без ошибок
- `docker compose --profile monitoring up` → Grafana показывает метрики
- CI pipeline зелёный для всех сервисов
- Swagger UI доступен для Transfer Service, Pricing Service, LLM Service

**Результат:** Чистый код без TODO/мёртвого кода. JaCoCo coverage > 70% отображается в GitLab CI. Проект в финальном состоянии.

---

## Рекомендуемый порядок работы

Sprint 7 — всего 1 неделя, поэтому порядок важен. Рекомендуемый план по дням:

**День 1–2: Interview Q&A (самый важный артефакт)**
1. **B1** — Architecture + System Design (утро)
2. **B2** — Kafka + DB + Patterns (вечер)

**День 3: Диаграммы + Q&A продолжение**
3. **B5** — Level 2 диаграммы (утро — визуальная работа для разнообразия)
4. **B3** — Infra + Observability + Security + Process (вечер)

**День 4: Диаграммы + README + последние Q&A**
5. **B6** — Level 3 flow diagrams (утро)
6. **B4** — LLM + ClickHouse + Behavioral (полдень)
7. **B8** — README + CV bullets (вечер)

**День 5: Финализация**
8. **B7** — Level 4 + Было/Стало (утро)
9. **B9** — ADR review + demo scenario (полдень)
10. **B10** — Code cleanup + JaCoCo (вечер)

---

## Итого Sprint 7

| Метрика | Значение |
|---------|----------|
| Блоков | 10 |
| Interview Q&A | 90+ вопросов с развёрнутыми ответами |
| Архитектурные диаграммы | Level 2 (2 шт), Level 3 (4 flow diagrams), Level 4 (1 infra), Было/Стало (2 шт) |
| README | Полный, с Quick Start, architecture overview, tech stack |
| CV Bullet Points | 15+ формулировок по всем аспектам проекта |
| ADR ревизия | 12–15+ ADR актуализированы |
| Демо сценарий | 8 актов, 15–20 минут end-to-end walkthrough |
| Code cleanup | 0 TODO/FIXME, нет мёртвого кода |
| Test coverage | JaCoCo > 70% в CI для каждого JVM-сервиса |

---

## Финальный чеклист: Interview Ready

По завершении Sprint 7 — проверить готовность:

- [ ] Могу за 2 минуты рассказать, что за проект и какую задачу решает
- [ ] Могу нарисовать high-level архитектуру на whiteboard за 3 минуты
- [ ] Могу назвать 5+ паттернов и объяснить зачем каждый
- [ ] На каждое «почему X, а не Y?» — аргументированный ответ с trade-offs
- [ ] Есть 3+ STAR-истории: сложная проблема → решение → результат
- [ ] Могу объяснить circuit breaker, saga, outbox на конкретных примерах
- [ ] Могу описать troubleshooting workflow: алерт → метрика → трейс → лог
- [ ] Могу рассказать про команду, процессы, ретроспективы с конкретикой
- [ ] README позволяет клонировать и запустить за 5 минут
- [ ] CI pipeline зелёный, coverage > 70%
