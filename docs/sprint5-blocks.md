# Sprint 5 — Observability + Security: Декомпозиция на блоки

## Sprint Goal

Полный observability стек работает: Prometheus + Grafana (метрики и дашборды), Loki (логи), Tempo (distributed tracing). Алерты настроены. JWT-аутентификация защищает API. RBAC разграничивает доступ. Rate limiting через Redis.

**Что это даёт:** после Sprint 5 система полностью наблюдаема — от алерта до root cause за минуты через связку метрики → трейсы → логи в Grafana. API защищён JWT + RBAC. Milestone M5: Observable & Secure.

---

## Обзор блоков

| Block | Содержание | Tasks | Зависимости |
|-------|-----------|-------|-------------|
| **B1** | Observability infra: Docker Compose profile `monitoring` — Prometheus, Grafana, Loki, Tempo | S5-T01 | — |
| **B2** | Prometheus: scrape targets + custom business metrics в сервисах | S5-T02, S5-T06 | B1 |
| **B3** | Grafana dashboards: Transfer Service RED, Kafka, Infrastructure | S5-T03, S5-T04, S5-T05 | B2 |
| **B4** | Distributed tracing: Micrometer Tracing + OTel + Kafka header propagation | S5-T07, S5-T08 | B1 |
| **B5** | Grafana: Tempo datasource, exemplars linking metrics → traces → logs | S5-T09 | B3, B4 |
| **B6** | Alerting: Alertmanager + Prometheus rules + Slack webhook | S5-T10, S5-T11 | B2 |
| **B7** | Security: JWT validation + SecurityFilterChain + Mock Identity token endpoint | S5-T12, S5-T13 | — |
| **B8** | Security: RBAC (@PreAuthorize) + Rate limiting (Redis sliding window) | S5-T14, S5-T15 | B7 |
| **B9** | Security: Integration tests — auth, forbidden, rate limit | S5-T16 | B8 |
| **B10** | Tech Debt: Helm charts (Outbox, Pricing, Notification Gateway) + PII masking | S5-T17, S5-T18 | — |

---

## Зависимости между блоками

```
Observability ветка:
B1 (Docker Compose monitoring) ──→ B2 (Prometheus + Business Metrics)
                                      ↓
                                   B3 (Grafana Dashboards) ──→ B5 (Exemplars: Metrics→Traces→Logs)
                                                                  ↑
B1 ──→ B4 (Distributed Tracing) ─────────────────────────────────┘

B2 ──→ B6 (Alerting: Alertmanager + Rules + Slack)

Security ветка:
B7 (JWT + SecurityFilterChain) ──→ B8 (RBAC + Rate Limiting) ──→ B9 (Security Integration Tests)

B10 (Helm charts + PII masking) — независим
```

Две основные ветки:
- **Observability ветка:** B1 → B2 → B3 + B4 → B5 → B6
- **Security ветка:** B7 → B8 → B9

Рекомендуемый порядок: начать с Observability infra (B1, B2), потом Security setup (B7), потом Dashboards + Tracing параллельно (B3, B4), потом RBAC + Rate Limiting (B8), потом финализация (B5, B6, B9, B10).

---

## Детали каждого блока

### Block 1 — Observability Infrastructure: Docker Compose Monitoring Profile

**Инфраструктура:** `docker-compose.yml`

**Контекст:** До сих пор observability ограничивалась structured logging (Sprint 2) и Prometheus-метриками Go-сервиса (Sprint 3). Сейчас поднимаем полный стек: Prometheus (сбор метрик), Grafana (визуализация), Loki (агрегация логов), Tempo (distributed tracing). Через Docker Compose profile — чтобы не нагружать машину разработчика.

**Что делать:**

*Docker Compose profile `monitoring` — добавить в docker-compose.yml:*

- `prometheus` (prom/prometheus:v2.50.0): порт 9090, volumes для prometheus.yml и alert-rules.yml, flags: `--enable-feature=exemplar-storage`, `--web.enable-lifecycle`
- `grafana` (grafana/grafana:10.3.0): порт 3000, admin/grafana, anonymous viewer, volumes для provisioning (datasources, dashboards)
- `loki` (grafana/loki:2.9.4): порт 3100, minimal local storage config
- `promtail` (grafana/promtail:2.9.4): Docker socket mount для автообнаружения контейнеров, pipeline stages для JSON parsing (level, traceId, service → labels)
- `tempo` (grafana/tempo:2.3.1): порты 3200 (API), 4317 (OTLP gRPC), 4318 (OTLP HTTP), local storage
- `alertmanager` (prom/alertmanager:v0.27.0): порт 9093
- `kafka-exporter` (danielqsj/kafka-exporter:v1.7.0): порт 9308, экспорт consumer lag и topic metrics

Все компоненты с `profiles: ["monitoring"]`.

*Структура конфигурационных файлов:*
```
infra/monitoring/
├── prometheus/
│   ├── prometheus.yml          # scrape targets (заполняется в B2)
│   └── alert-rules.yml         # alert rules (заполняется в B6)
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/
│   │   │   └── datasources.yml # auto-provision Prometheus, Loki, Tempo
│   │   └── dashboards/
│   │       └── dashboards.yml
│   └── dashboards/             # JSON файлы (заполняются в B3)
├── loki/
│   └── loki-config.yml
├── promtail/
│   └── promtail-config.yml
├── tempo/
│   └── tempo-config.yml
└── alertmanager/
    └── alertmanager.yml        # routing (заполняется в B6)
```

*Grafana datasources auto-provisioning (datasources.yml):*
- Prometheus (default) с exemplarTraceIdDestinations → Tempo
- Loki с derivedFields → regex для traceId → link to Tempo
- Tempo с tracesToLogsV2 → Loki (filter by traceId) и tracesToMetrics → Prometheus

Это ключевая конфигурация — она связывает три столпа observability: метрики ↔ трейсы ↔ логи.

*Запуск:*
```bash
docker compose up                        # без мониторинга
docker compose --profile monitoring up   # с мониторингом
```

**Результат:** `docker compose --profile monitoring up` поднимает 7 компонентов. Grafana (localhost:3000) с тремя связанными datasources.

---

### Block 2 — Prometheus: Scrape Targets + Custom Business Metrics

**Сервисы:** инфраструктура + все сервисы

**Контекст:** Prometheus поднят (B1), нужно настроить откуда собирать метрики и добавить бизнес-метрики.

**Что делать:**

*prometheus.yml — scrape_configs:*
- `transfer-service`: `/actuator/prometheus`, scrape 10s
- `outbox-service`: `/actuator/prometheus`, scrape 10s
- `pricing-service`: `/metrics` (Ktor Micrometer), scrape 10s
- `notification-gateway`: `/metrics` (Go prometheus client), scrape 10s
- `kafka-exporter`: default metrics endpoint, scrape 15s

*Проверить Micrometer Prometheus в Spring Boot:*
- Зависимость `io.micrometer:micrometer-registry-prometheus`
- `management.endpoints.web.exposure.include: health,info,prometheus`
- Verify: `curl localhost:8080/actuator/prometheus`

*Pricing Service (Ktor) — Micrometer endpoint:*
- MicrometerMetrics plugin + PrometheusMeterRegistry
- Route `/metrics` → registry.scrape()

*Custom business metrics в Transfer Service:*
```kotlin
@Component
class TransferMetrics(private val meterRegistry: MeterRegistry) {
    fun recordTransferCreated(corridor: String, deliveryMethod: String) {
        meterRegistry.counter("transfers_created_total", "corridor", corridor, "delivery_method", deliveryMethod).increment()
    }
    fun recordTransferCompleted(corridor: String) {
        meterRegistry.counter("transfers_completed_total", "corridor", corridor).increment()
    }
    fun recordTransferFailed(corridor: String, reason: String) {
        meterRegistry.counter("transfers_failed_total", "corridor", corridor, "reason", reason).increment()
    }
    fun recordTransferCompletionTime(durationSeconds: Double, corridor: String) {
        meterRegistry.timer("transfer_completion_time_seconds", "corridor", corridor)
            .record(Duration.ofMillis((durationSeconds * 1000).toLong()))
    }
    fun recordQuoteCreated() {
        meterRegistry.counter("quotes_created_total").increment()
    }
}
```
- Вызывать из TransferService/Kafka consumers при создании, завершении, ошибке

**Результат:** Prometheus scrape'ит 4 сервиса + Kafka. Business metrics экспортируются. `localhost:9090/targets` — все UP.

---

### Block 3 — Grafana Dashboards: Transfer RED, Kafka, Infrastructure

**Инфраструктура:** `infra/monitoring/grafana/dashboards/`

**Контекст:** Метрики собираются, нужны дашборды.

**Что делать:**

*Dashboard 1 — Transfer Service (RED + Business):*
- Rate: `rate(http_server_requests_seconds_count{service="transfer-service"}[5m])`
- Errors: error rate %
- Duration: p50/p95/p99 — `histogram_quantile()`
- Business: transfers created by corridor (stacked), completed vs failed, completion time p95
- Circuit breaker: state gauge, calls by kind (successful/failed)

*Dashboard 2 — Kafka:*
- Consumer lag by group (`kafka_consumergroup_lag` от kafka-exporter) — самая важная
- Messages in rate by topic
- DLT messages counter (должен быть 0)
- Spring Kafka listener duration

*Dashboard 3 — Infrastructure:*
- JVM heap memory by service
- GC pause duration
- HikariCP active/pending connections
- Go goroutines + memory (notification-gateway)

*Процесс:* создать через Grafana UI (localhost:3000) → Export JSON → сохранить в `infra/monitoring/grafana/dashboards/`. При restart — provisioning восстанавливает.

**Результат:** 3 дашборда: бизнес, Kafka, инфраструктура. Мгновенный overview системы.

---

### Block 4 — Distributed Tracing: Micrometer Tracing + OTel + Kafka Propagation

**Сервисы:** `services/transfer-service/`, `services/outbox-service/`, `services/pricing-service/`

**Контекст:** Без tracing невозможно проследить путь запроса через сервисы и Kafka.

**Что делать:**

*Зависимости (Spring Boot):*
```kotlin
implementation("io.micrometer:micrometer-tracing-bridge-otel")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
```

*application.yml:*
```yaml
management:
  tracing:
    sampling:
      probability: 1.0
    propagation:
      type: w3c
  otlp:
    tracing:
      endpoint: http://tempo:4318/v1/traces
```

*Kafka header propagation — ключевой момент:*
```yaml
spring:
  kafka:
    listener:
      observation-enabled: true
    template:
      observation-enabled: true
```
- Или программно: `containerProperties.observationEnabled = true` и `template.setObservationEnabled(true)`
- traceId/spanId передаются через Kafka headers (W3C Trace Context)

*Pricing Service (Ktor):*
- OTel Ktor plugin или ручная передача через gRPC interceptors
- Если сложно — задокументировать как known limitation

*Verify:* POST /api/v1/transfers → в Tempo: spans для REST, gRPC, PostgreSQL, Kafka produce. traceId в каждом лог-entry.

**Результат:** Сквозной трейс: REST → gRPC → PostgreSQL → Kafka produce → Kafka consume.

---

### Block 5 — Grafana: Exemplars + Traces-to-Logs Linking

**Инфраструктура:** Grafana

**Контекст:** Три столпа настроены. Связываем и тестируем workflow.

**Что делать:**

*Verify workflow:*
1. Grafana → histogram panel → Show Exemplars → точки → клик → Tempo trace
2. Loki Explorer → лог с traceId → кнопка → Tempo
3. Tempo → trace → View Logs → Loki с фильтром

*Задокументировать:* `docs/troubleshooting-workflow.md` — от алерта до root cause за 3 клика.

**Результат:** Полная связка метрики ↔ трейсы ↔ логи.

---

### Block 6 — Alerting: Alertmanager + Prometheus Rules + Slack

**Инфраструктура:** `infra/monitoring/prometheus/alert-rules.yml`, `infra/monitoring/alertmanager/alertmanager.yml`

**Что делать:**

*Alert rules (8+):*
- HighErrorRate: > 1% за 5 мин → warning
- CriticalErrorRate: > 5% за 2 мин → critical
- HighLatency: p99 > 500ms за 5 мин → warning
- HighConsumerLag: > 10000 за 5 мин → warning
- CriticalConsumerLag: > 50000 за 10 мин → critical
- DLTMessagesPresent: increase > 0 → warning
- HighMemoryUsage: JVM heap > 80% → warning
- CircuitBreakerOpen: state > 0 → warning

*Alertmanager:*
- Route: group by alertname + service, group_wait 30s, repeat 4h
- critical → slack-critical, default → slack-default
- Dev: placeholder webhook, алерты видны в Alertmanager UI (localhost:9093)

**Результат:** 8+ alert rules. Alertmanager маршрутизирует по severity.

---

### Block 7 — Security: JWT Validation + SecurityFilterChain

**Сервис:** `services/transfer-service/`

**Контекст:** API открыт, нужна JWT-аутентификация.

**Что делать:**

*Зависимости:*
- `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`

*RSA keys:* openssl genrsa → private.pem (Mock Identity), public.pem (Transfer Service validation)

*SecurityFilterChain:*
- CSRF disabled, stateless sessions
- Public: `/actuator/**`, `/swagger-ui/**`
- Authenticated: `/api/v1/**`
- JWT decoder: NimbusJwtDecoder с RSA public key
- Problem Details для 401/403 (не HTML)

*Mock Identity Service:*
- `POST /auth/token` — принимает userId, roles → подписанный JWT (RS256, 15 мин TTL)
- Profile dev/test only
- Зависимость: `io.jsonwebtoken:jjwt-api`

**Результат:** API endpoints требуют JWT. Mock token endpoint для тестирования.

---

### Block 8 — Security: RBAC + Rate Limiting

**Сервис:** `services/transfer-service/`

**Что делать:**

*RBAC:*
- JWT roles claim → GrantedAuthority converter (ROLE_SENDER, ROLE_OPERATOR)
- `GET /transfers`: SENDER — только свои, OPERATOR — все
- `POST /transfers`: SENDER only, senderId из JWT (не из body)
- `POST /transfers/{id}/cancel`: SENDER — свой, OPERATOR — любой

*Rate Limiting — Redis sliding window:*
- Lua-скрипт: sorted set timestamps, ZREMRANGEBYSCORE
- 100 req/min (authenticated), 20 req/min (IP)
- Headers: X-RateLimit-Limit, X-RateLimit-Remaining
- 429 → Problem Details + Retry-After: 60
- OncePerRequestFilter, skip /actuator/**

**Результат:** RBAC + rate limiting работают.

---

### Block 9 — Security: Integration Tests

**Сервис:** `services/transfer-service/`

**Что делать:**

*TestJwtHelper:* генерация тестовых JWT (sender, operator, expired)

*Tests (7+):*
1. Unauthenticated → 401
2. Expired token → 401
3. SENDER sees only own transfers
4. OPERATOR sees all transfers
5. SENDER cannot cancel other's transfer → 403
6. Rate limit: 100 pass, 101st → 429
7. Health/metrics — public

**Результат:** Security covered by integration tests.

---

### Block 10 — Tech Debt: Helm Charts + PII Masking

**Что делать:**

*Helm charts:*
- `infra/helm/outbox-service/`: без Ingress, HPA по consumer lag
- `infra/helm/pricing-service/`: fast startup, gRPC port 50051
- `infra/helm/notification-gateway/`: minimal resources (64Mi/128Mi)

*PII masking:*
- Best practice: не логировать PII — только ID'шники
- Fallback: custom Logback converter для email/phone/document masking
- Unit tests

**Результат:** Helm charts для всех сервисов. PII masking.

---

## Рекомендуемый порядок работы

1. **B1** — Monitoring infra (фундамент)
2. **B2** — Prometheus scrape + business metrics
3. **B7** — JWT + SecurityFilterChain (параллельно)
4. **B4** — Distributed tracing
5. **B3** — Grafana dashboards
6. **B8** — RBAC + Rate Limiting
7. **B5** — Exemplars linking
8. **B6** — Alerting
9. **B9** — Security integration tests
10. **B10** — Helm charts + PII masking

---

## Итого Sprint 5

| Метрика | Значение |
|---------|----------|
| Блоков | 10 |
| Новые infra-компоненты | 7 (Prometheus, Grafana, Loki, Promtail, Tempo, Alertmanager, Kafka Exporter) |
| Grafana dashboards | 3 (Transfer RED, Kafka, Infrastructure) |
| Alert rules | 8+ |
| Distributed tracing | W3C Trace Context: REST + gRPC + Kafka |
| Security | JWT (RS256), RBAC (SENDER/OPERATOR), Rate Limiting (Redis) |
| Тесты | 7+ security integration tests |
| Helm charts | 3 новых |
| Tech Debt | PII masking |

---

## Формулировки для собеседования (Sprint 5 highlights)

**Observability:**
> «Мы реализовали полный observability стек: Prometheus + Grafana (метрики и алерты), Loki (structured JSON logs), Tempo (distributed tracing). Все три столпа связаны через exemplars и derived fields: от точки на графике → к трейсу → к логам. От алерта до root cause — 2-3 минуты.»

**Distributed Tracing:**
> «Сквозной трейс: REST → gRPC Pricing → PostgreSQL → Kafka produce → Kafka consume. W3C Trace Context propagation через HTTP и Kafka headers. Spring Kafka observationEnabled. Production sampling 10%, errors — 100%.»

**Security:**
> «JWT (RS256), RBAC через @PreAuthorize: SENDER — только свои переводы, OPERATOR — все. Rate limiting: Redis sliding window, 100 req/min per user. senderId из JWT, не из body.»

**Alerting:**
> «Multi-tier: warning → Slack, critical → PagerDuty. Error rate > 1%/5%, p99 > 500ms, consumer lag > 10K, DLT > 0, heap > 80%, circuit breaker open.»
