# Sprint 5 — Observability & Security: Детальный отчёт

**Milestone:** M5 — Observable & Secure
**Ветка:** `feature/s5-block1-monitoring-infra`
**PR:** #22
**Статистика:** 12 коммитов, 67 файлов, +2 728 строк

---

## Цель спринта

До Sprint 5 система была функционально полной (переводы, saga, outbox, gRPC, Kafka), но слепой: ни метрик, ни трейсов, ни алертов, ни аутентификации. Любой инцидент требовал ручного разбора логов, а API был полностью открытым.

После Sprint 5:
- Полный observability стек: от алерта до root cause за 2–3 минуты через связку метрики → трейсы → логи
- API защищён JWT + RBAC + rate limiting
- Helm-чарты для всех сервисов
- PII-маскирование в логах

---

## Теоретическая база

### Три столпа observability

Observability — способность понять внутреннее состояние системы по её внешним выходам. В распределённых системах есть три типа телеметрии:

**1. Метрики (Metrics)** — числовые агрегаты за период времени. Отвечают на вопрос «что происходит прямо сейчас?». Примеры: request rate, error rate, latency percentiles. Метрики дёшевы в хранении (фиксированный размер на временной ряд), но теряют контекст отдельных запросов. Prometheus использует pull-модель: сам ходит на `/metrics` endpoint каждые N секунд (scrape). Это проще в конфигурации (не нужно знать адрес Prometheus в каждом сервисе) и позволяет Prometheus контролировать нагрузку.

**2. Логи (Logs)** — дискретные текстовые события. Отвечают на вопрос «что конкретно произошло?». Structured JSON logs с полями `level`, `traceId`, `service` позволяют фильтровать и агрегировать. Loki — log-aggregation система от Grafana Labs, индексирующая только labels (не полнотекстовый поиск как Elasticsearch), что делает её значительно дешевле в эксплуатации.

**3. Трейсы (Traces)** — путь одного запроса через все сервисы. Отвечают на вопрос «где именно тормозит?». Trace состоит из spans — единиц работы с начальным/конечным временем и контекстом. Каждый span знает parent span через `traceId` + `spanId`. Tempo — tracing backend от Grafana Labs, хранит трейсы в object storage без индексации (поиск по traceId), что минимизирует стоимость.

Сила — в связке: метрика показывает аномалию → exemplar (ссылка на конкретный traceId внутри метрики) → трейс показывает путь запроса → лог показывает ошибку с полным контекстом.

### Pull vs Push модель метрик

**Pull (Prometheus):** сервис экспортирует endpoint `/metrics`, Prometheus периодически его опрашивает. Плюсы: сервис не знает о Prometheus, нет dependency; Prometheus контролирует нагрузку; легко добавлять/убирать targets. Минусы: нужна service discovery; не подходит для short-lived jobs.

**Push (OTLP/Tempo):** сервис сам отправляет данные на collector endpoint. Для трейсов push — единственный вариант, потому что span создаётся и завершается внутри одного запроса, и хранить его в памяти сервиса нет смысла.

В нашей системе: метрики — pull (Prometheus), трейсы — push (OTLP → Tempo), логи — push (stdout → Promtail → Loki).

### RED метод

RED (Rate, Errors, Duration) — методология мониторинга request-driven сервисов (Tom Wilkie, Weaveworks):
- **Rate** — количество запросов в секунду. Показывает нагрузку.
- **Errors** — доля запросов с ошибкой. Показывает качество.
- **Duration** — время обработки запроса (p50, p95, p99). Показывает производительность.

Альтернатива — USE (Utilization, Saturation, Errors) — подходит для ресурсов (CPU, память, диск), а не для сервисов. В нашем случае RED для сервисных дашбордов, USE-подобные метрики — на Infrastructure дашборде (JVM heap, HikariCP connections).

### Percentiles: p50, p95, p99

Среднее (avg) скрывает хвост распределения: если 99% запросов обрабатываются за 10ms, а 1% — за 10 секунд, avg ≈ 100ms. Пользователь видит 10 секунд, а avg говорит «всё хорошо».

- **p50 (медиана)** — 50% запросов быстрее этого значения. Типичный user experience.
- **p95** — 95% запросов быстрее. Показывает degradation для заметной доли пользователей.
- **p99** — 99% запросов быстрее. «Почти худший случай». Это значение используется в SLA.

Prometheus вычисляет percentiles через `histogram_quantile()` по histogram buckets. Точность зависит от границ buckets — Spring Boot Micrometer генерирует sensible defaults.

### JWT (JSON Web Token)

JWT — стандарт (RFC 7519) для передачи claims между сторонами в виде подписанного JSON-объекта.

Структура: `header.payload.signature` (Base64URL-encoded).

```
Header:  {"alg": "RS256", "typ": "JWT"}
Payload: {"sub": "user-id", "roles": ["SENDER"], "exp": 1741234567, "iat": 1741233667}
Signature: RS256(base64(header) + "." + base64(payload), privateKey)
```

**Верификация:** получатель декодирует header и payload, вычисляет подпись с public key, сравнивает с signature. Если совпадает — payload не был изменён.

**Stateless:** сервер не хранит сессию. Вся информация (userId, roles, expiration) — внутри токена. Это критично для микросервисной архитектуры: каждый сервис может проверить токен независимо, без обращения к центральному session store.

### Симметричное vs асимметричное подписывание

**HS256 (HMAC-SHA256)** — симметричный алгоритм. Один и тот же secret используется для создания и проверки подписи. Проблема: каждый сервис, который проверяет токены, должен знать secret. Компрометация любого сервиса = компрометация всей системы авторизации.

**RS256 (RSA-SHA256)** — асимметричный алгоритм. Private key подписывает (только Identity Service), public key проверяет (все сервисы). Компрометация transfer-service не позволяет создавать новые токены — только проверять существующие. Это принцип наименьших привилегий (principle of least privilege).

### RBAC (Role-Based Access Control)

RBAC — модель контроля доступа, где права привязаны к ролям, а не к конкретным пользователям. Пользователь получает роли, роли дают permissions.

В нашей системе:
```
SENDER  → может: POST /transfers (свои), GET /transfers (свои)
OPERATOR → может: GET /transfers (все), НЕ может: POST /transfers
```

Spring Security реализует RBAC через `GrantedAuthority`. JWT claim `roles: ["SENDER"]` конвертируется в `ROLE_SENDER`, проверяется через `@PreAuthorize("hasRole('SENDER')")` — декларативная авторизация на уровне метода.

### Rate Limiting: алгоритмы

**Fixed Window Counter:** один counter на временное окно (например, минуту). Просто, но имеет boundary burst problem: 100 запросов в :59 и 100 в :00 = 200 за 2 секунды.

**Sliding Window Log:** хранит timestamp каждого запроса, считает количество в окне [now - window, now]. Точный, но дорогой по памяти.

**Sliding Window Counter (наша реализация):** Redis sorted set, где score = timestamp. `ZREMRANGEBYSCORE` чистит старые записи, `ZCARD` считает текущие, `ZADD` добавляет новый. Сочетает точность sliding window с эффективностью — O(log N) на операцию, автоматическая очистка через TTL.

**Token Bucket / Leaky Bucket:** позволяют burst до определённого предела, затем ограничивают rate. Более гибкие, но сложнее в реализации. Для нашего use case (простой rate limit без burst allowance) sliding window — оптимальный выбор.

### Lua-скрипты в Redis и атомарность

Redis — однопоточный (event loop). Каждая команда атомарна, но последовательность команд — нет. Между `ZCARD` и `ZADD` другой клиент может выполнить свой `ZADD`, нарушив лимит.

Lua-скрипт в Redis выполняется атомарно: весь скрипт — одна операция с точки зрения других клиентов. Это гарантирует, что проверка лимита и добавление записи происходят без race condition. Аналог — хранимая процедура в SQL, но в in-memory store.

### Helm и Kubernetes deployment patterns

**Helm** — пакетный менеджер для Kubernetes. Chart — набор шаблонов (Go templates) + values (параметры). Один chart, разные values для окружений:
```
values.yaml            — dev (2 реплики, 512Mi)
values-staging.yaml    — staging (3 реплики, 768Mi)
values-production.yaml — production (4 реплики, 1Gi)
```

**Probes в Kubernetes:**
- **startupProbe** — проверяет, что приложение запустилось. Пока не пройдёт — liveness/readiness не проверяются. Критично для JVM (долгий старт).
- **livenessProbe** — приложение живо? Если нет → Kubernetes перезапускает pod. Использует `/actuator/health/liveness`.
- **readinessProbe** — приложение готово принимать трафик? Если нет → убирает из Service endpoints (не получает трафик). Использует `/actuator/health/readiness`.

**HPA (Horizontal Pod Autoscaler):** автоматически масштабирует количество pod'ов по метрике. Для CPU/memory — встроенная поддержка. Для custom metrics (consumer lag) — нужен Prometheus Adapter, преобразующий Prometheus-метрики в Kubernetes Custom Metrics API.

### PII и compliance

**PII (Personally Identifiable Information)** — данные, идентифицирующие физическое лицо: email, телефон, SSN, номер карты. Регуляции (GDPR, PCI DSS) требуют защиты PII на всех уровнях, включая логи.

Подходы к защите PII в логах:
1. **Не логировать PII** — идеально, но требует дисциплины на каждом `log.info()`.
2. **Structured field exclusion** — исключать конкретные поля при сериализации. Работает для structured logging, не работает для произвольных строк.
3. **Pattern-based masking (наш подход)** — regex на уровне Logback converter. Safety net: маскирует PII независимо от способа попадания в лог. Покрывает случаи `toString()`, конкатенации, exception messages.

Logback `ClassicConverter` — расширение системы форматирования. Зарегистрированный как `%piiMask`, вызывается для каждого лог-сообщения, заменяя PII-паттерны масками до записи в output.

### Defense in Depth

Принцип многоуровневой защиты: каждый уровень работает независимо, компенсируя возможные пробелы в других.

В нашей системе:
```
Уровень 1: Rate Limiting (OncePerRequestFilter)     — защита от brute force
Уровень 2: JWT Authentication (SecurityFilterChain)  — проверка identity
Уровень 3: RBAC (@PreAuthorize)                      — проверка permissions
Уровень 4: senderId из JWT (controller)              — data isolation
Уровень 5: anyRequest().denyAll()                    — fail-safe default
Уровень 6: PII masking (Logback)                     — защита данных в логах
```

Если один уровень пробит (например, JWT скомпрометирован), другие продолжают работать. `denyAll()` как default — принцип fail-safe: если разработчик забудет добавить правило для нового endpoint, он будет заблокирован, а не открыт.

---

## Block 1 — Observability Infrastructure

### Что сделано

Поднят полный мониторинг-стек из 7 компонентов через Docker Compose profile `monitoring`:

| Компонент | Образ | Порт | Назначение |
|-----------|-------|------|------------|
| Prometheus | prom/prometheus:v2.50.0 | 9091 | Сбор метрик (scrape) |
| Grafana | grafana/grafana:10.3.0 | 3000 | Визуализация |
| Loki | grafana/loki:2.9.4 | 3100 | Агрегация логов |
| Promtail | grafana/promtail:2.9.4 | — | Сбор логов из Docker |
| Tempo | grafana/tempo:2.3.1 | 4317/4318 | Distributed tracing |
| Alertmanager | prom/alertmanager:v0.27.0 | 9093 | Маршрутизация алертов |
| Kafka Exporter | danielqsj/kafka-exporter:v1.7.0 | 9308 | Экспорт consumer lag |

### Почему profile

Мониторинг-стек потребляет ~2 ГБ RAM. Разработчику не нужен Prometheus при локальной отладке бизнес-логики. Profile позволяет:
```bash
docker compose up                        # без мониторинга — быстрый старт
docker compose --profile monitoring up   # с мониторингом — для observability работ
```

### Почему порт 9091 для Prometheus

Стандартный 9090 уже занят gRPC-портом pricing-service. Вместо перенастройки gRPC (что потребовало бы изменений в proto и клиентах) Prometheus слушает на 9091.

### Теория: Exemplars — мост между метриками и трейсами

Prometheus histogram хранит агрегированные данные: сколько запросов попало в каждый bucket. Информация о конкретных запросах теряется. **Exemplar** — это привязка конкретного traceId к точке на histogram. При записи метрики Spring Micrometer добавляет traceId текущего запроса как exemplar:

```
http_server_requests_seconds_bucket{le="0.5"} 1420  # {traceId="abc123"} 0.42
```

В Grafana: наводишь на точку histogram → видишь exemplar → клик → переход в Tempo с этим traceId. Без exemplars нужно угадывать, какой трейс соответствует аномалии на графике.

Для работы exemplars нужно:
1. Prometheus с `--enable-feature=exemplar-storage`
2. Micrometer Tracing (добавляет traceId в exemplar)
3. Grafana datasource с `exemplarTraceIdDestinations` → Tempo

### Ключевая конфигурация — связка трёх datasources

Grafana provisioning автоматически создаёт три datasource с перекрёстными ссылками:

```
Prometheus (uid: prometheus)
  └─ exemplarTraceIdDestinations → Tempo (uid: tempo)

Loki (uid: loki)
  └─ derivedFields: regex traceId → ссылка на Tempo

Tempo (uid: tempo)
  └─ tracesToLogsV2 → Loki (uid: loki, фильтр по traceId)
  └─ serviceMap → Prometheus (uid: prometheus)
```

Это означает: клик по точке на графике Prometheus → трейс в Tempo → логи в Loki. Три столпа observability связаны в единый workflow.

### Файлы

```
infra/docker/docker-compose.yml                          — 7 сервисов + 4 volumes
infra/monitoring/prometheus/prometheus.yml                — scrape targets
infra/monitoring/grafana/provisioning/datasources/        — 3 связанных datasource
infra/monitoring/grafana/provisioning/dashboards/         — dashboard provider
infra/monitoring/loki/loki-config.yml                     — TSDB schema, 7д retention
infra/monitoring/promtail/promtail-config.yml             — Docker socket discovery
infra/monitoring/tempo/tempo-config.yml                   — OTLP receivers
infra/monitoring/alertmanager/alertmanager.yml            — routing по severity
```

---

## Block 2 — Custom Business Metrics

### Что сделано

Создан `TransferMetrics` — компонент с бизнес-метриками:

| Метрика | Тип | Labels | Где вызывается |
|---------|-----|--------|---------------|
| `transfers_created_total` | Counter | corridor, delivery_method | TransferService.createTransfer() |
| `transfers_completed_total` | Counter | corridor | PayoutEventConsumer (COMPLETED) |
| `transfers_failed_total` | Counter | corridor, reason | PaymentEventConsumer, PayoutEventConsumer |
| `transfer_completion_time_seconds` | Timer | corridor | PayoutEventConsumer (COMPLETED) |
| `quotes_created_total` | Counter | — | TransferService.createTransfer() |

### Теория: типы метрик в Prometheus

Prometheus поддерживает 4 типа метрик:

**Counter** — монотонно растущее значение (только increment). Примеры: `transfers_created_total`, `http_requests_total`. Нельзя уменьшить. Для rate используется `rate()` или `increase()` в PromQL.

**Gauge** — значение, которое может расти и падать. Примеры: `jvm_memory_used_bytes`, `hikaricp_connections_active`. Отражает текущее состояние.

**Histogram** — распределение значений по buckets. Пример: `http_server_requests_seconds_bucket{le="0.1"}` — сколько запросов были быстрее 100ms. Позволяет вычислять percentiles через `histogram_quantile()`. Micrometer автоматически создаёт histogram для HTTP-метрик Spring Boot.

**Summary** — как histogram, но percentiles вычисляются на стороне клиента. Не агрегируемо между инстансами. В Spring Boot не используется — предпочитаем histogram.

**Naming convention:** Prometheus требует snake_case с суффиксами: `_total` для counters, `_seconds` для duration, `_bytes` для размеров. Micrometer использует dot-notation внутри (`transfers.created.total`) и автоматически конвертирует в snake_case для Prometheus registry.

### Почему отдельный компонент, а не инлайн

`TransferMetrics` инкапсулирует знание о naming convention, тегах и MeterRegistry. Без него каждый consumer и сервис создавал бы метрики напрямую через `meterRegistry.counter(...)`, что привело бы к:
- Дублированию строковых констант с именами метрик
- Риску опечаток в именах тегов (corridor vs corridor_name)
- Невозможности переименовать метрику в одном месте

### Почему corridor как основной label

Коридор (US→PH, US→MX) — ключевой бизнес-срез. Позволяет на дашборде увидеть: «отказы по коридору US→PH выросли на 300%» вместо абстрактного «ошибки выросли».

### Файлы

```
services/transfer-service/.../service/TransferMetrics.kt         — компонент метрик
services/transfer-service/.../service/TransferService.kt         — +metrics в конструктор
services/transfer-service/.../consumer/PaymentEventConsumer.kt   — +metrics failed
services/transfer-service/.../consumer/PayoutEventConsumer.kt    — +metrics completed/failed/time
```

---

## Block 3 — Grafana Dashboards

### Что сделано

Три JSON-дашборда, provisioned автоматически при старте Grafana:

**Dashboard 1 — Transfer Service (RED + Business):** 8 панелей
- Request Rate (req/s), Error Rate (%), Latency p50/p95/p99
- Transfers Created by Corridor (stacked bar), Completed vs Failed
- Transfer Completion Time p95
- Circuit Breaker State, Circuit Breaker Calls by Kind

**Dashboard 2 — Kafka:** 4 панели
- Consumer Lag by Group (ключевая для обнаружения проблем)
- Messages In Rate by Topic
- DLT Messages (должно быть 0)
- Kafka Listener Duration

**Dashboard 3 — Infrastructure:** 6 панелей
- JVM Heap Memory, Heap Max vs Used %
- GC Pause Duration
- HikariCP Active/Pending Connections
- Go Goroutines, Go Memory (notification-gateway)

### Почему RED метод

Rate, Errors, Duration (RED) — стандарт для request-driven сервисов (рекомендация Grafana Labs и Weaveworks). Покрывает три фундаментальных вопроса: «Сколько работы делает сервис?», «Сколько из неё с ошибками?», «Как быстро?».

### Почему отдельный дашборд для Kafka

Consumer lag — самая важная метрика в event-driven архитектуре. Рост lag означает, что consumers не успевают за producers. Выделенный дашборд позволяет oncall-инженеру мгновенно оценить здоровье Kafka без поиска по общему дашборду.

### Файлы

```
infra/monitoring/grafana/dashboards/transfer-service.json   — 8 панелей
infra/monitoring/grafana/dashboards/kafka.json              — 4 панели
infra/monitoring/grafana/dashboards/infrastructure.json     — 6 панелей
```

---

## Block 4 — Distributed Tracing

### Что сделано

Сквозной трейсинг: REST → gRPC → PostgreSQL → Kafka produce → Kafka consume.

**Зависимости:**
- `micrometer-tracing-bridge-otel` — мост Micrometer → OpenTelemetry
- `opentelemetry-exporter-otlp` — экспорт спанов по OTLP HTTP в Tempo

**Конфигурация:**
```yaml
management:
  tracing:
    sampling:
      probability: 1.0      # dev: 100%, production: 0.1
    propagation:
      type: w3c              # W3C Trace Context (traceparent header)
```

**Kafka propagation:**
```yaml
spring.kafka:
  listener.observation-enabled: true     # consumer → создаёт child span
  template.observation-enabled: true     # producer → пробрасывает traceparent в headers
```

### Теория: как работает distributed tracing

Trace — это дерево spans. Каждый span представляет единицу работы (HTTP запрос, SQL query, Kafka produce):

```
Trace: abc123
├── [span-1] POST /api/v1/transfers (transfer-service, 150ms)
│   ├── [span-2] gRPC ValidateQuote (pricing-service, 30ms)
│   ├── [span-3] SELECT recipient (PostgreSQL, 5ms)
│   ├── [span-4] INSERT transfer (PostgreSQL, 8ms)
│   └── [span-5] Kafka SEND transfer.events (2ms)
└── [span-6] Kafka RECEIVE transfer.events (outbox-service, 20ms)
    └── [span-7] Kafka SEND payments.payment.requested (3ms)
```

**Propagation (распространение контекста):** при HTTP-вызове traceId передаётся через заголовок `traceparent: 00-{traceId}-{spanId}-{flags}`. При Kafka — через record headers. Каждый сервис извлекает traceId, создаёт child span, передаёт дальше.

**Sampling:** в production 100% трейсов — это огромный объём данных. Sampling 10% (`probability: 0.1`) уменьшает объём в 10 раз, сохраняя статистическую репрезентативность. Head-based sampling (решение о записи трейса принимается в начале) vs tail-based (после завершения, можно сохранять только ошибки). Spring Boot Micrometer использует head-based — проще, но теряет часть error traces.

### Почему Micrometer Tracing, а не OTel Java Agent

OTel Java Agent — javaagent, модифицирующий байткод. Плюсы: zero-code instrumentation. Минусы:
- Добавляет 50–100 МБ к образу
- Конфликтует с некоторыми Spring Boot автоконфигурациями
- Сложнее дебажить (bytecode manipulation)

Micrometer Tracing — нативная Spring Boot интеграция. `spring-boot-starter-actuator` уже включает Micrometer. Добавление bridge-otel — это две зависимости и YAML-конфиг. Все instrumentation points (RestClient, JPA, Kafka) подключаются через Spring Boot auto-configuration.

### Почему W3C, а не B3

W3C Trace Context (`traceparent` header) — стандарт W3C, поддерживается всеми major фреймворками. B3 — legacy Zipkin формат. Spring Boot 3.x по умолчанию использует W3C.

### Почему observation-enabled для Kafka

Без этого флага Kafka producer/consumer создают спаны, но НЕ пробрасывают trace context через Kafka headers. С флагом:
- Producer вставляет `traceparent` в Kafka record headers
- Consumer извлекает `traceparent` и создаёт child span

Это единственный способ получить сквозной трейс через Kafka без ручного кода.

### Файлы

```
gradle/libs.versions.toml                                       — +3 библиотеки
services/transfer-service/build.gradle.kts                      — +2 зависимости
services/outbox-service/build.gradle.kts                        — +2 зависимости
services/transfer-service/src/main/resources/application.yml    — tracing + kafka observation
services/outbox-service/src/main/resources/application.yml      — tracing + kafka observation
infra/docker/docker-compose.yml                                 — OTEL_EXPORTER_OTLP_ENDPOINT
services/transfer-service/src/test/resources/application-test.yml — tracing disabled в тестах
```

---

## Block 5 — Troubleshooting Workflow

### Что сделано

Документ `docs/troubleshooting-workflow.md` описывающий 3-click workflow:

```
Алерт (Alertmanager)
  → Grafana Dashboard (видим аномалию на графике)
    → Exemplar точка → Tempo (видим полный трейс запроса)
      → View Logs → Loki (видим ошибку в логах с полным контекстом)
```

### Почему это важно

Без документированного workflow каждый инцидент — это импровизация. С документом даже новый инженер может найти root cause за 2–3 минуты вместо 30 минут ручного grep по логам.

---

## Block 6 — Alerting

### Что сделано

8 alert rules в трёх группах:

**transfer-service-alerts:**
| Правило | Условие | Severity |
|---------|---------|----------|
| HighErrorRate | > 1% за 5 мин | warning |
| CriticalErrorRate | > 5% за 2 мин | critical |
| HighLatency | p99 > 500ms за 5 мин | warning |
| CircuitBreakerOpen | state > 0 | warning |

**kafka-alerts:**
| Правило | Условие | Severity |
|---------|---------|----------|
| HighConsumerLag | > 10 000 за 5 мин | warning |
| CriticalConsumerLag | > 50 000 за 10 мин | critical |
| DLTMessagesPresent | increase > 0 | warning |

**infrastructure-alerts:**
| Правило | Условие | Severity |
|---------|---------|----------|
| HighMemoryUsage | JVM heap > 80% за 5 мин | warning |

**Alertmanager routing:**
- `severity: critical` → канал `slack-critical` (group_wait: 10s)
- Всё остальное → канал `slack-default` (group_wait: 30s)
- Группировка по `alertname` + `service`
- Repeat interval: 4 часа

### Теория: многоуровневый алертинг

Alerting в Prometheus/Alertmanager работает по схеме:

```
Prometheus (evaluation)          Alertmanager (routing + dedup)       Receiver
  alert-rules.yml        ──→       alertmanager.yml             ──→   Slack/PagerDuty
  PromQL expr + for                group_by + routes                  webhook
```

**PromQL `for` clause:** алерт срабатывает только если условие истинно в течение `for` периода. `HighErrorRate > 1% for 5m` — не реагируем на единичный 500-й ответ, только на устойчивый рост. Это уменьшает alert fatigue (усталость от ложных алертов).

**Alertmanager grouping:** объединяет однотипные алерты в одно уведомление. Без grouping: при падении базы данных придёт 50 алертов (по одному от каждого endpoint). С `group_by: [alertname, service]` — один алерт «HighErrorRate on transfer-service».

**Severity-based routing:** critical → немедленная эскалация (PagerDuty, звонок oncall), warning → канал в Slack для review. Без разделения — либо всё пейджерит (alert fatigue), либо критичные алерты теряются в потоке warnings.

### Почему именно эти пороги

- **1% error rate** — статистически значимый рост ошибок на объёме 100+ req/min. Ниже — шум.
- **500ms p99** — SLA для финтех API. Ниже — пользователь ждёт, выше — деградация UX.
- **10K consumer lag** — при 1000 msg/s это 10-секундное отставание. Допустимо, но требует внимания.
- **50K consumer lag** — 50 секунд отставания. Критичная потеря real-time характеристики.
- **DLT > 0** — любое сообщение в Dead Letter Topic — это потерянная бизнес-операция.
- **80% heap** — после 80% GC начинает доминировать, latency растёт нелинейно.

### Файлы

```
infra/monitoring/prometheus/alert-rules.yml     — 8 правил
infra/monitoring/alertmanager/alertmanager.yml   — routing + receivers
```

---

## Block 7 — JWT Authentication

### Что сделано

**SecurityFilterChain:**
- CSRF отключён (stateless API, нет cookies)
- Сессии: STATELESS (JWT содержит всё нужное)
- Публичные endpoints: `/actuator/**`, `/swagger-ui/**`, `/auth/**`, `/api/v1/transfers/*/events` (SSE)
- Аутентифицированные: `/api/v1/**`
- Всё остальное: `denyAll()` (defense in depth)

**JWT validation:**
- Алгоритм: RS256 (асимметричный — сервис знает только public key)
- Decoder: NimbusJwtDecoder с RSA public key
- Responses: Problem Details JSON для 401/403 (не HTML)

**Mock Identity Service:**
- `POST /auth/token` — принимает `{userId, roles}`, возвращает подписанный JWT
- Active profiles: `dev`, `test`, `docker`
- TTL: 15 минут
- Библиотека: JJWT 0.12.6

### Теория: Spring Security Filter Chain

Spring Security работает как цепочка servlet-фильтров. Каждый запрос проходит через них последовательно:

```
HTTP Request
  → SecurityContextPersistenceFilter (загружает/создаёт SecurityContext)
  → CorsFilter
  → CsrfFilter (отключён — stateless API)
  → BearerTokenAuthenticationFilter (извлекает JWT из Authorization header)
    → JwtDecoder.decode() (проверяет подпись, expiration)
    → JwtAuthenticationConverter (roles claim → GrantedAuthority)
    → SecurityContext.setAuthentication(JwtAuthenticationToken)
  → ExceptionTranslationFilter (ловит AuthenticationException, AccessDeniedException)
  → AuthorizationFilter (requestMatchers: permitAll / authenticated / denyAll)
  → [наш RateLimitFilter]
  → DispatcherServlet → Controller
    → @PreAuthorize (method-level security через AOP proxy)
```

**Ключевой момент:** `@PreAuthorize` работает не на уровне фильтра, а через Spring AOP. Это означает, что `AccessDeniedException` бросается уже после DispatcherServlet, и если `@RestControllerAdvice` с `@ExceptionHandler(Exception.class)` перехватит его раньше `ExceptionTranslationFilter` — получим 500 вместо 403. Именно этот баг мы нашли и исправили.

### Почему RS256, а не HS256

HS256 (symmetric) — один секрет для sign и verify. Если transfer-service скомпрометирован, атакующий может создавать новые токены. RS256 — transfer-service хранит только public key и может только проверять, но не создавать токены. Private key — только у Identity Service.

### Почему mock endpoint, а не внешний Identity Service

На данном этапе Identity Service — это отдельный микросервис со своей БД, OAuth2 flows и user management. Его разработка — отдельный спринт. Mock endpoint позволяет:
- Тестировать security flow end-to-end
- Демонстрировать API через Swagger
- Запускать integration tests с реальными JWT

Mock ограничен profiles `dev/test/docker` и не попадёт в production.

### Почему Problem Details для 401/403

Spring Security по умолчанию возвращает HTML для ошибок аутентификации. API-клиенты ожидают JSON. Кастомные `authenticationEntryPoint` и `accessDeniedHandler` возвращают RFC 9457 Problem Details — единый формат ошибок во всём API.

### Файлы

```
gradle/libs.versions.toml                                    — +5 библиотек (security + jjwt)
services/transfer-service/build.gradle.kts                   — +6 зависимостей
services/transfer-service/.../config/SecurityConfig.kt       — SecurityFilterChain + JWT decoder
services/transfer-service/.../auth/MockTokenController.kt    — POST /auth/token
services/transfer-service/src/main/resources/keys/           — RSA key pair (dev/test only)
```

---

## Block 8 — RBAC + Rate Limiting

### RBAC

**Роли:**
- `SENDER` — создаёт и просматривает свои переводы
- `OPERATOR` — просматривает все переводы, не может создавать

**Реализация:**
- JWT claim `roles: ["SENDER"]` → `ROLE_SENDER` через кастомный `Converter<Jwt, AbstractAuthenticationToken>`
- `@PreAuthorize("hasRole('SENDER')")` на `POST /transfers`
- `@PreAuthorize("hasAnyRole('SENDER', 'OPERATOR')")` на `GET /transfers`
- `senderId` берётся из `jwt.subject` (не из тела запроса и не из заголовка)

### Почему senderId из JWT, а не из X-Sender-Id

Заголовок `X-Sender-Id` мог быть подделан любым аутентифицированным пользователем. JWT `sub` claim подписан private key Identity Service и не может быть модифицирован клиентом. Это устраняет класс атак «горизонтальная эскалация привилегий» — пользователь A не может видеть переводы пользователя B.

### Rate Limiting

**Алгоритм:** Redis sliding window на sorted sets.

**Lua-скрипт (атомарная операция):**
1. `ZREMRANGEBYSCORE` — удаляет записи старше 60 секунд
2. `ZCARD` — считает оставшиеся
3. Если `count < limit` → `ZADD` с timestamp + random suffix → возвращает `{0, count+1, limit}` (allowed)
4. Иначе → возвращает `{1, count, limit}` (denied)

**Лимиты:**
- Аутентифицированный: 100 req/min (ключ: `rate_limit:user:{userId}`)
- Анонимный: 20 req/min (ключ: `rate_limit:ip:{ip}`)

**Реализация:** `OncePerRequestFilter`, пропускает `/actuator`, `/auth`, `/swagger-ui`, `/api-docs`.

**Graceful degradation:** если Redis недоступен — запрос пропускается (лучше пропустить, чем заблокировать всех пользователей).

### Почему sliding window, а не fixed window

Fixed window (один counter на минуту) имеет burst-проблему: пользователь может отправить 100 запросов в последнюю секунду окна и ещё 100 в первую секунду следующего — 200 за 2 секунды. Sliding window на sorted sets устраняет эту проблему: каждый запрос записывается с точным timestamp, и окно скользит.

### Почему Lua-скрипт, а не несколько Redis-команд

Без Lua три операции (ZREMRANGEBYSCORE → ZCARD → ZADD) выполняются не атомарно. Между ZCARD и ZADD другой запрос может проскочить, создав race condition. Lua-скрипт в Redis выполняется атомарно — гарантия consistency.

### Файлы

```
services/transfer-service/.../api/controller/TransferController.kt  — @PreAuthorize + JWT subject
services/transfer-service/.../config/RateLimitFilter.kt             — sliding window filter
```

---

## Block 9 — Security Integration Tests

### Что сделано

7 интеграционных тестов на полном Spring Boot контексте (Testcontainers PostgreSQL + embedded Kafka + Redis):

| Тест | Ожидание | Проверяет |
|------|----------|-----------|
| No token → GET /transfers/{id} | 401 | Аутентификация обязательна |
| Expired token → GET /transfers/{id} | 401 | JWT expiration валидируется |
| SENDER → POST + GET /transfers | 201 + 200 | Полный CRUD flow с токеном |
| OPERATOR → GET /transfers | 200 | Оператор видит переводы |
| OPERATOR → POST /transfers | 403 | Оператор не может создавать |
| GET /actuator/health | 200 | Health endpoint публичный |
| GET /actuator/info | 200 | Info endpoint публичный |

### TestJwtHelper

Утилита для генерации тестовых JWT с реальным RSA private key:
```kotlin
TestJwtHelper.senderToken(senderId)    // ROLE_SENDER, 15 мин TTL
TestJwtHelper.operatorToken()          // ROLE_OPERATOR, 15 мин TTL
TestJwtHelper.expiredToken()           // уже истёкший токен
```

### Баг найденный при тестировании: AccessDeniedException → 500

`GlobalExceptionHandler` с `@ExceptionHandler(Exception::class)` перехватывал `AccessDeniedException` от `@PreAuthorize` раньше, чем Spring Security's `ExceptionTranslationFilter`. Результат: 500 вместо 403. Исправлено добавлением explicit handler который re-throw'ит исключение в Spring Security.

### Файлы

```
services/transfer-service/.../security/SecurityIntegrationTest.kt  — 7 тестов
services/transfer-service/.../security/TestJwtHelper.kt            — JWT генерация
services/transfer-service/.../api/error/GlobalExceptionHandler.kt  — +AccessDeniedException handler
```

---

## Block 10 — Helm Charts + PII Masking

### Helm Charts

Три новых Helm-чарта по паттерну transfer-service:

| Сервис | Язык | Порты | Ресурсы (dev) | HPA |
|--------|------|-------|---------------|-----|
| outbox-service | Kotlin/Spring Boot | 8081 | 256Mi/512Mi | по consumer lag |
| pricing-service | Kotlin/Ktor + gRPC | 8082 + 9090 | 256Mi/512Mi | по CPU |
| notification-gateway | Go | 8085 + 8086 | 64Mi/128Mi | по CPU |

Каждый чарт включает:
- `Chart.yaml` — metadata
- `values.yaml` / `values-staging.yaml` / `values-production.yaml` — environment-specific config
- `templates/deployment.yaml` — с probes (liveness + readiness + startup), Prometheus annotations, security context
- `templates/service.yaml` — ClusterIP
- `templates/configmap.yaml` — environment variables
- `templates/hpa.yaml` — autoscaling

### Теория: Kubernetes resource management

**Requests vs Limits:**
- **requests** — гарантированный минимум ресурсов. Kubernetes scheduler размещает pod на ноде, где requests удовлетворяемы. Если pod использует меньше — ресурсы доступны другим.
- **limits** — жёсткий потолок. При превышении memory limit → OOMKill (pod убит). При превышении CPU limit → throttling (pod замедлен).

Правило: `requests` — типичное потребление, `limits` — пиковое. Ratio `limits/requests` обычно 1.5x–2x. Больше ratio → меньше предсказуемость; ближе к 1:1 → меньше эффективность использования кластера.

**Почему notification-gateway 64Mi:** Go-бинарь компилируется в native code, нет виртуальной машины. Scratch-образ (пустой) + один статический бинарь = минимальный footprint. JVM-сервисы требуют memory для heap, metaspace, thread stacks, off-heap buffers — минимум 256Mi.

### Почему outbox HPA по consumer lag, а не по CPU

Outbox-service — это relay: читает из PostgreSQL, пишет в Kafka. CPU-нагрузка минимальна даже при высоком throughput (I/O bound). CPU-based HPA не сработает вовремя. Consumer lag напрямую отражает: «сколько сообщений ждут отправки». Рост lag → нужно больше реплик. Требует Prometheus Adapter в Kubernetes.

### Почему notification-gateway — 64Mi/128Mi

Go-бинарь без runtime overhead (нет JVM, нет GC паузы). Scratch-образ с единственным бинарём. 64 МБ — более чем достаточно для Go HTTP-сервера + Kafka consumer. Для сравнения: JVM-сервисы стартуют от 256 МБ.

### PII Masking

`PiiMaskingConverter` — кастомный Logback converter, маскирующий PII в логах:

| Тип | Пример входа | Пример выхода |
|-----|-------------|---------------|
| Email | `john.doe@example.com` | `j***@example.com` |
| Телефон | `+1-555-123-4567` | `***4567` |
| SSN | `123-45-6789` | `***-**-****` |
| Карта | `4111-1111-1111-1234` | `****-****-****-1234` |

Интеграция в logback-spring.xml:
```xml
<conversionRule conversionWord="piiMask"
                converterClass="com.swiftpay.transfer.logging.PiiMaskingConverter"/>
<pattern>... %piiMask%n</pattern>
```

9 unit-тестов, включая проверку что UUID не маскируются (false positive через phone regex).

### Почему regex, а не structured field exclusion

Structured field exclusion (не логировать поля `email`, `phone`) требует дисциплины: каждый `log.info()` должен использовать structured logging. Один забытый `log.info("User: {}", user.toString())` — утечка. Regex-конвертер работает как safety net: маскирует PII независимо от того, как данные попали в лог.

### Файлы

```
infra/helm/outbox-service/          — 8 файлов
infra/helm/pricing-service/         — 8 файлов
infra/helm/notification-gateway/    — 8 файлов
services/transfer-service/.../logging/PiiMaskingConverter.kt      — конвертер
services/transfer-service/.../logging/PiiMaskingConverterTest.kt  — 9 тестов
services/transfer-service/src/main/resources/logback-spring.xml   — интеграция
```

---

## Code Review

После реализации всех 10 блоков проведён code review. Найдено 12 проблем:

### Критичные (исправлены)

1. **Auth bypass через fallback UUID** — контроллер при отсутствии JWT subject молча подставлял хардкод `00000000-...`. Любой аутентифицированный пользователь с невалидным `sub` claim работал под чужой identity. Исправлено: `throw IllegalStateException`.

2. **Rate limit off-by-one** — Lua-скрипт возвращал одинаковую структуру `{count, limit}` для allowed и denied запросов. Проверка `count > limit` была всегда false при `count == limit`, что полностью отключало rate limiting после достижения лимита. Исправлено: скрипт возвращает `{denied_flag, count, limit}`.

3. **`anyRequest().permitAll()`** — catch-all правило делало все неописанные endpoints публичными. Любой новый endpoint был бы открыт без аутентификации. Исправлено: `anyRequest().denyAll()`.

### Средние (исправлены)

4. **Prometheus datasource без uid** — Tempo ссылался на `datasourceUid: prometheus`, но у Prometheus datasource не был задан uid. Service map не работал.

5. **AccessDeniedException → 500** — GlobalExceptionHandler перехватывал security-исключения.

6. **Operator token с невалидным UUID** — тесты не проверяли реальный RBAC.

### Подробности в `docs/sprint5-code-review.md`.

---

## Тестирование

### Автоматические тесты

Все существующие + новые тесты проходят:

```
./gradlew :services:transfer-service:test
BUILD SUCCESSFUL — 0 failures
```

| Категория | Кол-во | Покрытие |
|-----------|--------|----------|
| TransferServiceTest | unit | Бизнес-логика + metrics mock |
| PaymentEventConsumerTest | unit | Kafka consumer + metrics |
| PayoutEventConsumerTest | unit | Kafka consumer + metrics |
| TransferApiIntegrationTest | integration | REST API + DB + Kafka |
| SagaIntegrationTest | integration | Saga flow + events |
| SecurityIntegrationTest | integration | JWT + RBAC + public endpoints |
| PiiMaskingConverterTest | unit | Email, phone, SSN, card, UUID |

### Ручная верификация (checklist)

- [ ] `docker compose --profile monitoring up` — 7 сервисов запускаются
- [ ] Grafana (localhost:3000) — 3 дашборда видны
- [ ] Prometheus (localhost:9091/targets) — все targets UP
- [ ] `POST /auth/token` возвращает JWT
- [ ] `GET /api/v1/transfers` без токена → 401
- [ ] `GET /api/v1/transfers` с токеном → 200

---

## Итоговая статистика

| Метрика | Значение |
|---------|----------|
| Блоков | 10 |
| Коммитов | 12 |
| Файлов изменено | 67 |
| Строк добавлено | +2 728 |
| Новые infra-компоненты | 7 (Prometheus, Grafana, Loki, Promtail, Tempo, Alertmanager, Kafka Exporter) |
| Grafana dashboards | 3 (18 панелей суммарно) |
| Alert rules | 8 |
| Helm charts | 3 новых (outbox, pricing, notification-gateway) |
| Security | JWT RS256 + RBAC (SENDER/OPERATOR) + Rate Limiting (Redis sliding window) |
| Интеграционные тесты | +7 security + 9 PII masking |
| Баги найдены на ревью | 12 (6 исправлены, 6 deferred) |
