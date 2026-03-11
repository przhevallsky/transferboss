# Tracing, MDC и TraceId

## TraceId — зачем нужен

TraceId — уникальный идентификатор, который проходит через весь запрос от начала до конца. Позволяет найти все логи конкретного запроса среди миллионов записей.

Пример: клиент получает ошибку с `traceId: "a1b2c3d4-..."`, присылает в поддержку, и по этому ID можно найти все логи запроса через grep/Kibana/Grafana.

## MDC (Mapped Diagnostic Context)

MDC — это из SLF4J. По сути thread-local `Map<String, String>`, который автоматически добавляется к каждой строке лога.

```kotlin
MDC.put("traceId", "abc-123")
log.info("Transfer created")
// Лог: 2026-03-07 12:00:00 [traceId=abc-123] INFO Transfer created
MDC.clear()
```

Кладёшь traceId в MDC один раз — все `log.info/warn/error` в рамках этого запроса автоматически включают его. Не нужно передавать traceId параметром в каждый метод.

## Как реализовано у нас (TraceFilter)

`config/TraceFilter.kt` — кастомный servlet filter (15 строк кода):

1. HTTP-запрос приходит → filter перехватывает
2. Берёт `X-Trace-Id` из хедера или генерирует новый UUID
3. `MDC.put("traceId", traceId)` — все логи в рамках запроса содержат traceId
4. `response.setHeader("X-Trace-Id", traceId)` — возвращает клиенту в ответе
5. `finally { MDC.clear() }` — чистит MDC чтобы не утечь в следующий запрос на том же потоке

В Kafka-консьюмерах HTTP-фильтра нет, поэтому traceId ставится вручную из поля события: `MDC.put("traceId", event.eventId)`.

## Почему кастомный TraceFilter, а не Micrometer Tracing

Micrometer Tracing (бывший Spring Cloud Sleuth) — полноценный distributed tracing. Для его работы нужно:
- `micrometer-tracing-bridge-brave` или `micrometer-tracing-bridge-otel`
- Exporter (Zipkin, Jaeger, Tempo)
- Поднятый сервер трейсинга в инфраструктуре
- Конфигурация sampling rate, propagation format

Это всё нужно в продакшене для визуализации цепочек (waterfall диаграммы, latency breakdown). Но для MVP — overkill. Наш TraceFilter покрывает основную потребность: найти логи запроса и отдать traceId клиенту при ошибке.

## Миграция на Micrometer Tracing (будущее)

Когда проект дойдёт до продакшена — заменим `TraceFilter` на Micrometer Tracing. Fallback `MDC.get("trace_id")` (snake_case) в `getTraceId()` уже заложен для совместимости — Micrometer/OpenTelemetry используют snake_case ключи в MDC.
