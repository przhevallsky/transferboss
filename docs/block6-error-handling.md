# Block 6 — Global Error Handling (@RestControllerAdvice → RFC 9457 Problem Details)

## Контекст проекта

**TransferHub** — платформа международных денежных переводов. Kotlin + Spring Boot 3.3.x, JDK 21.

**Sprint 1, Block 6.** Предыдущие блоки завершены:
- Blocks 1-3: Миграции, Domain model, Repositories
- Block 4: TransferService с BusinessException иерархией
- Block 5: REST Controller (POST /api/v1/transfers, GET /api/v1/transfers/{id}, GET /api/v1/transfers)

Сейчас ошибки возвращаются в стандартном Spring Boot формате (Whitelabel Error). Нужно заменить на единый RFC 9457 Problem Details формат.

## Задача

Создать централизованный error handler, который превращает ВСЕ ошибки в единый формат RFC 9457 Problem Details. Этот формат — стандарт проекта для всех сервисов.

## Структура файлов

Создать в `services/transfer-service/src/main/kotlin/com/transferhub/transfer/`:

```
api/
  error/
    GlobalExceptionHandler.kt     — @RestControllerAdvice
    ProblemDetailExtensions.kt    — helper для создания ProblemDetail
```

---

## Целевой формат ответа (RFC 9457)

Каждая ошибка возвращает JSON:

```json
{
  "type": "https://api.transferhub.com/errors/unsupported-corridor",
  "title": "Unsupported Corridor",
  "status": 422,
  "detail": "Corridor US→JP is not supported",
  "instance": "/api/v1/transfers",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2025-01-15T14:30:00Z"
}
```

Для validation errors — добавляются violations:

```json
{
  "type": "https://api.transferhub.com/errors/validation-error",
  "title": "Validation Error",
  "status": 400,
  "detail": "Request body has 2 validation error(s)",
  "instance": "/api/v1/transfers",
  "traceId": "...",
  "timestamp": "...",
  "violations": [
    { "field": "sendAmount", "message": "send_amount must be positive" },
    { "field": "deliveryMethod", "message": "delivery_method is required" }
  ]
}
```

---

## Что создать

### 1. GlobalExceptionHandler.kt

```kotlin
package com.transferhub.transfer.api.error

import com.transferhub.transfer.exception.BusinessException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI
import java.time.Instant

/**
 * Централизованная обработка ошибок для ВСЕХ REST endpoints.
 *
 * Все ошибки конвертируются в RFC 9457 Problem Details — единый формат,
 * согласованный между всеми сервисами TransferHub.
 *
 * Порядок @ExceptionHandler важен: Spring выбирает наиболее специфичный.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * Бизнес-ошибки (наши кастомные exceptions).
     * TransferNotFoundException → 404, UnsupportedCorridorException → 422, etc.
     */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        ex: BusinessException,
        request: WebRequest
    ): ResponseEntity<ProblemDetail> {

        log.warn("Business error: type={}, message={}", ex.errorType, ex.message)

        val problem = ProblemDetail.forStatus(ex.statusCode).apply {
            type = URI.create(ex.errorType)
            title = ex.title
            detail = ex.message
            instance = extractPath(request)
            setProperty("traceId", getTraceId())
            setProperty("timestamp", Instant.now().toString())
        }

        return ResponseEntity.status(ex.statusCode).body(problem)
    }

    /**
     * Bean Validation ошибки (из @Valid на request body).
     * MethodArgumentNotValidException содержит список field errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        ex: MethodArgumentNotValidException,
        request: WebRequest
    ): ResponseEntity<ProblemDetail> {

        val violations = ex.bindingResult.fieldErrors.map { error ->
            mapOf(
                "field" to error.field,
                "message" to (error.defaultMessage ?: "Invalid value"),
                "rejectedValue" to error.rejectedValue?.toString()
            )
        }

        log.warn("Validation error: {} violation(s) on {}", violations.size, extractPath(request))

        val problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            type = URI.create("https://api.transferhub.com/errors/validation-error")
            title = "Validation Error"
            detail = "Request body has ${violations.size} validation error(s)"
            instance = extractPath(request)
            setProperty("traceId", getTraceId())
            setProperty("timestamp", Instant.now().toString())
            setProperty("violations", violations)
        }

        return ResponseEntity.badRequest().body(problem)
    }

    /**
     * Missing required header (например, X-Idempotency-Key не передан).
     */
    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(
        ex: MissingRequestHeaderException,
        request: WebRequest
    ): ResponseEntity<ProblemDetail> {

        log.warn("Missing header: {}", ex.headerName)

        val problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            type = URI.create("https://api.transferhub.com/errors/missing-header")
            title = "Missing Required Header"
            detail = "Required header '${ex.headerName}' is missing"
            instance = extractPath(request)
            setProperty("traceId", getTraceId())
            setProperty("timestamp", Instant.now().toString())
        }

        return ResponseEntity.badRequest().body(problem)
    }

    /**
     * Type mismatch (например, UUID вместо строки в path variable или header).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
        request: WebRequest
    ): ResponseEntity<ProblemDetail> {

        log.warn("Type mismatch: parameter={}, value={}", ex.name, ex.value)

        val problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            type = URI.create("https://api.transferhub.com/errors/type-mismatch")
            title = "Invalid Parameter Format"
            detail = "Parameter '${ex.name}' must be of type ${ex.requiredType?.simpleName ?: "unknown"}"
            instance = extractPath(request)
            setProperty("traceId", getTraceId())
            setProperty("timestamp", Instant.now().toString())
        }

        return ResponseEntity.badRequest().body(problem)
    }

    /**
     * Invalid cursor format или другие IllegalArgumentException.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        ex: IllegalArgumentException,
        request: WebRequest
    ): ResponseEntity<ProblemDetail> {

        log.warn("Illegal argument: {}", ex.message)

        val problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
            type = URI.create("https://api.transferhub.com/errors/invalid-argument")
            title = "Invalid Argument"
            detail = ex.message ?: "Invalid argument provided"
            instance = extractPath(request)
            setProperty("traceId", getTraceId())
            setProperty("timestamp", Instant.now().toString())
        }

        return ResponseEntity.badRequest().body(problem)
    }

    /**
     * Optimistic locking conflict (конкурентное обновление одного перевода).
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException::class)
    fun handleOptimisticLock(
        ex: org.springframework.orm.ObjectOptimisticLockingFailureException,
        request: WebRequest
    ): ResponseEntity<ProblemDetail> {

        log.warn("Optimistic locking failure: {}", ex.message)

        val problem = ProblemDetail.forStatus(HttpStatus.CONFLICT).apply {
            type = URI.create("https://api.transferhub.com/errors/concurrent-modification")
            title = "Concurrent Modification"
            detail = "The resource was modified by another request. Please retry."
            instance = extractPath(request)
            setProperty("traceId", getTraceId())
            setProperty("timestamp", Instant.now().toString())
        }

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
    }

    /**
     * Catch-all для непредвиденных ошибок.
     *
     * ВАЖНО: НЕ возвращаем stack trace, class name или другие internal details.
     * Это security best practice: internal structure не должна утекать наружу.
     * Полная информация — только в логах (с traceId для поиска).
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(
        ex: Exception,
        request: WebRequest
    ): ResponseEntity<ProblemDetail> {

        log.error("Unhandled exception: {} - {}", ex.javaClass.simpleName, ex.message, ex)

        val problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR).apply {
            type = URI.create("https://api.transferhub.com/errors/internal-error")
            title = "Internal Server Error"
            detail = "An unexpected error occurred. Please contact support with traceId: ${getTraceId()}"
            instance = extractPath(request)
            setProperty("traceId", getTraceId())
            setProperty("timestamp", Instant.now().toString())
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem)
    }

    // --- Helpers ---

    private fun extractPath(request: WebRequest): URI? {
        return try {
            val description = request.getDescription(false) // "uri=/api/v1/transfers"
            URI.create(description.removePrefix("uri="))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Получить traceId из MDC (Micrometer Tracing / OpenTelemetry).
     * В Sprint 5 будет настроен полноценный tracing. Пока fallback на "no-trace".
     */
    private fun getTraceId(): String {
        return MDC.get("traceId") ?: MDC.get("trace_id") ?: "no-trace-id"
    }
}
```

---

### 2. Spring Boot ProblemDetail configuration

В `application.yml` добавь (если нет), чтобы Spring Boot использовал RFC 9457:

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true    # Включает нативную поддержку Problem Details в Spring Boot 3
```

Также убедись, что response Content-Type — `application/problem+json`:

Spring Boot 3 автоматически ставит `application/problem+json` для ProblemDetail. Если нет — можно форсировать через `produces` на @ExceptionHandler или через конфигурацию.

---

## Маппинг исключений → HTTP статусы (справка)

| Exception | HTTP Status | Problem Type |
|-----------|-------------|-------------|
| `TransferNotFoundException` | 404 | transfer-not-found |
| `RecipientNotFoundException` | 404 | recipient-not-found |
| `UnsupportedCorridorException` | 422 | unsupported-corridor |
| `QuoteExpiredException` | 422 | quote-expired |
| `InvalidTransferStateException` | 409 | invalid-transfer-state |
| `MethodArgumentNotValidException` | 400 | validation-error |
| `MissingRequestHeaderException` | 400 | missing-header |
| `MethodArgumentTypeMismatchException` | 400 | type-mismatch |
| `ObjectOptimisticLockingFailureException` | 409 | concurrent-modification |
| `Exception` (catch-all) | 500 | internal-error |

---

## Проверка результата

1. Компилируется без ошибок.

2. Тест validation error:
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{}'

# Ожидаемый ответ: 400 с Problem Details и violations[]
```

3. Тест missing header:
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -d '{"quote_id": "..."}'

# Ожидаемый: 400, "Required header 'X-Idempotency-Key' is missing"
```

4. Тест business error (unsupported corridor):
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -H "X-Sender-Id: 00000000-0000-0000-0000-000000000001" \
  -d '{
    "quote_id": "22222222-2222-2222-2222-222222222222",
    "recipient_id": "11111111-1111-1111-1111-111111111111",
    "delivery_method": "BANK_DEPOSIT",
    "send_amount": 200.00,
    "send_currency": "USD",
    "receive_currency": "JPY",
    "source_country": "US",
    "dest_country": "JP"
  }'

# Ожидаемый: 422, "Corridor US→JP is not supported"
```

5. Тест 404:
```bash
curl http://localhost:8080/api/v1/transfers/99999999-9999-9999-9999-999999999999

# Ожидаемый: 404, Problem Details
```

6. Все ответы об ошибках содержат: type, title, status, detail, instance, traceId, timestamp.

7. 500-е ошибки НЕ содержат stack trace — только generic message + traceId.

## Чего НЕ делать

- Не добавляй Redis cache — Block 7
- Не дорабатывай пагинацию — Block 8
- Не пиши тесты — Block 9, 10
- Не настраивай Spring Security — Sprint 5
- Не настраивай Micrometer Tracing — Sprint 5 (пока traceId = "no-trace-id", это нормально)
