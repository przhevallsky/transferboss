# Production Checklist — что изменить перед продакшеном

## 1. Аутентификация (КРИТИЧНО)

**Файл:** `api/controller/TransferController.kt` (строки 52-56, 100-111)

Сейчас `senderId` берётся из хедера `X-Sender-Id` с fallback на хардкод UUID `00000000-0000-0000-0000-000000000001`. Это заглушка для разработки.

**Что сделать:**
- Подключить Spring Security + JWT (или OAuth2 Resource Server)
- Извлекать `senderId` из JWT-токена (claim), а не из хедера
- Убрать `X-Sender-Id` хедер и дефолтный UUID
- Добавить авторизацию: пользователь может видеть/создавать только свои переводы

## 2. Коридоры и лимиты (ВАЖНО)

**Файл:** `service/TransferService.kt` (строки 37-50)

Поддерживаемые коридоры и минимальные суммы захардкожены в коде.

**Что сделать:**
- Вынести в MongoDB или конфиг-сервис (Consul KV / Spring Cloud Config)
- Добавить максимальные суммы и дневные/месячные лимиты
- Добавить admin API для управления коридорами без редеплоя

## 3. Tracing (УЛУЧШЕНИЕ)

**Файл:** `config/TraceFilter.kt`

Кастомный TraceFilter — 15 строк, достаточен для MVP, но не для продакшена с микросервисами.

**Что сделать:**
- Заменить на Micrometer Tracing (`micrometer-tracing-bridge-otel`)
- Подключить exporter (Tempo / Jaeger / Zipkin)
- Получим автоматическую propagation через HTTP, gRPC, Kafka
- Fallback `MDC.get("trace_id")` в `GlobalExceptionHandler` уже заложен под это

## 4. Секреты и конфигурация (КРИТИЧНО)

**Файл:** `application.yml` (строки 10-12, 36-38)

Пароли к PostgreSQL и Redis прописаны в открытом виде (`transferhub`/`transferhub`).

**Что сделать:**
- Использовать переменные окружения или Vault (HashiCorp Vault / AWS Secrets Manager)
- Профили Spring: `application-prod.yml` с `${DB_PASSWORD}`, `${REDIS_HOST}` и т.д.
- Убрать дефолтные значения для чувствительных данных

## 5. Логирование (ВАЖНО)

**Файл:** `application.yml` (строки 113-117)

`com.swiftpay: DEBUG` и `org.hibernate.SQL: DEBUG` — слишком verbose для продакшена.

**Что сделать:**
- Поставить `com.swiftpay: INFO`, `org.hibernate.SQL: WARN`
- Настроить structured logging (JSON формат) для Kibana/Loki
- Добавить log rotation / size limits

## 6. Health endpoint (БЕЗОПАСНОСТЬ)

**Файл:** `application.yml` (строка 85)

`show-details: always` — раскрывает детали о подключениях к БД, Redis, Kafka всем.

**Что сделать:**
- Поменять на `show-details: when-authorized`
- Ограничить доступ к actuator endpoints (только внутренняя сеть)

## 7. Swagger UI (БЕЗОПАСНОСТЬ)

**Файл:** `application.yml` (строки 90-98)

Swagger UI открыт для всех.

**Что сделать:**
- Отключить в prod-профиле (`springdoc.swagger-ui.enabled: false`)
- Или ограничить доступ через Spring Security (только для внутреннего использования)

## 8. @Order на GlobalExceptionHandler (МЕЛОЧЬ)

**Файл:** `api/error/GlobalExceptionHandler.kt` (строка 21)

`@Order(Ordered.HIGHEST_PRECEDENCE)` — избыточна пока handler один. Не мешает, но и не нужна. Убрать или оставить — на усмотрение, станет полезной если появится второй handler.

## 9. Connection pool и таймауты (ТЮНИНГ)

**Файл:** `application.yml`

- HikariCP `maximum-pool-size: 10` — может быть мало под нагрузкой, нужен нагрузочный тест
- Redis `timeout: 2000ms` — проверить, достаточно ли
- Consul `session-ttl-seconds: 15` — может быть слишком долго для lock'ов под нагрузкой
- Kafka — добавить настройки `max.poll.records`, `session.timeout.ms` для production load
