package com.swiftpay.transfer.api.error

import com.swiftpay.transfer.exception.BusinessException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI
import java.time.Instant

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

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

    private fun extractPath(request: WebRequest): URI? {
        return try {
            val description = request.getDescription(false)
            URI.create(description.removePrefix("uri="))
        } catch (e: Exception) {
            null
        }
    }

    private fun getTraceId(): String {
        return MDC.get("traceId") ?: MDC.get("trace_id") ?: "no-trace-id"
    }
}