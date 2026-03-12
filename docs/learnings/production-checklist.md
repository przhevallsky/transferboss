# Production Checklist — что изменить перед продакшеном

## Статус: 7/9 закрыты

---

## 1. ~~Аутентификация~~ ✅ (Sprint 5)

Реализовано: JWT RS256 + RBAC (SENDER/OPERATOR/ADMIN) через Spring Security OAuth2 Resource Server. `senderId` извлекается из JWT subject. Rate limiting через Redis sliding window (100 req/min auth, 20 anon).

**Файлы:** `SecurityConfig.kt`, `RateLimitFilter.kt`, `AuthController.kt`

## 2. ~~Коридоры и лимиты~~ ✅ (Production Hardening)

Вынесены в `application.yml` через `@ConfigurationProperties` (`CorridorProperties.kt`). Коридоры и минимальные суммы настраиваются без изменения кода — через env vars или config override.

**Файлы:** `CorridorProperties.kt`, `TransferService.kt`, `application.yml` (секция `transfer.corridors`)

**Остаётся для полного прода:** admin API для управления коридорами, максимальные суммы, дневные/месячные лимиты.

## 3. ~~Tracing~~ ✅ (Sprint 4-5)

Реализовано: Micrometer Tracing + OpenTelemetry exporter → Tempo. Автоматическая propagation через HTTP, gRPC, Kafka. Grafana → Tempo интеграция через exemplars.

**Файлы:** `application.yml` (секция `management.tracing`), `build.gradle.kts` (micrometer-tracing-bridge-otel)

## 4. ~~Секреты и конфигурация~~ ✅ (Production Hardening)

Все credentials используют паттерн `${ENV_VAR:dev-default}`:
- `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}` — PostgreSQL
- `${REDIS_HOST}`, `${REDIS_PORT}` — Redis
- `${KAFKA_BOOTSTRAP_SERVERS}` — Kafka
- `${OPENAI_API_KEY}` — OpenAI (llm-service)

Дефолты сохранены для локальной разработки. В production env vars задаются через Kubernetes Secrets или Vault.

**Файлы:** `application.yml` (transfer-service, outbox-service, llm-service)

## 5. ~~Логирование~~ ✅ (Production Hardening)

- `com.swiftpay: INFO` (было DEBUG)
- `org.hibernate.SQL: WARN` (было DEBUG)
- Structured JSON logging через Logback (PII masking через `PiiMaskingConverter`)
- `application-prod.yml` дополнительно фиксирует production log levels

**Файлы:** `application.yml`, `application-prod.yml`, `logback-spring.xml`

## 6. ~~Health endpoint~~ ✅ (Production Hardening)

`show-details: when-authorized` во всех сервисах (transfer, outbox, mock-payment, mock-payout). Детали здоровья видны только авторизованным пользователям.

**Файлы:** `application.yml` (все сервисы)

## 7. ~~Swagger UI~~ ✅ (Production Hardening)

Отключается в production через `application-prod.yml`:
```yaml
springdoc:
  swagger-ui.enabled: false
  api-docs.enabled: false
```

Активация: `SPRING_PROFILES_ACTIVE=prod`

## 8. @Order на GlobalExceptionHandler — N/A

Не требует действий. `@Order(Ordered.HIGHEST_PRECEDENCE)` не мешает и станет полезной при добавлении второго handler.

## 9. Connection pool и таймауты — OPEN (тюнинг под нагрузку)

Требует нагрузочного тестирования для определения оптимальных значений:
- HikariCP `maximum-pool-size` — текущее 10, может потребоваться увеличение
- Kafka `max.poll.records`, `session.timeout.ms`
- Consul `session-ttl-seconds: 15`

---

## Дополнительные findings (Production Hardening scan)

| Находка | Статус | Комментарий |
|---------|--------|-------------|
| Security headers (X-Frame-Options) | ✅ Fixed | `.headers { frameOptions { deny() } }` в SecurityConfig |
| Actuator endpoints открыты | Acceptable | Ограничены до health,info,prometheus,metrics. В prod — network policy |
| CORS не настроен | N/A | Фронтенд отсутствует, API вызывается из backend/mobile |
| Request size limits | Acceptable | Tomcat defaults (2MB) достаточны для JSON API |
| HTTPS | N/A | Терминируется на ALB/Ingress level, не в приложении |
