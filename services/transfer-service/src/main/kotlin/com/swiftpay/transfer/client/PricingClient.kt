package com.swiftpay.transfer.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.transfer.exception.PricingUnavailableException
import com.swiftpay.transfer.exception.QuoteExpiredException
import com.transferhub.pricing.grpc.v1.PricingServiceGrpc
import com.transferhub.pricing.grpc.v1.ValidateQuoteRequest
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.TimeUnit

data class QuoteData(
    val quoteId: String,
    val sendAmount: BigDecimal,
    val receiveAmount: BigDecimal,
    val exchangeRate: BigDecimal,
    val feeAmount: BigDecimal,
    val feeCurrency: String,
    val sendCurrency: String,
    val receiveCurrency: String,
)

@Component
class PricingClient(
    pricingChannel: ManagedChannel,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(PricingClient::class.java)

    private val stub = PricingServiceGrpc.newBlockingStub(pricingChannel)

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("pricing-service")

    companion object {
        private const val QUOTE_CACHE_PREFIX = "quote:validated:"
        private val QUOTE_CACHE_TTL = Duration.ofMinutes(10)
    }

    fun validateQuote(quoteId: String): QuoteData {
        try {
            val result = circuitBreaker.executeSupplier {
                val request = ValidateQuoteRequest.newBuilder()
                    .setQuoteId(quoteId)
                    .build()

                val response = stub
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .validateQuote(request)

                if (!response.isValid) {
                    throw QuoteExpiredException(
                        quoteId,
                        response.rejectionReason.ifBlank { null }
                    )
                }

                val quote = response.quote
                QuoteData(
                    quoteId = quote.quoteId,
                    sendAmount = BigDecimal(quote.sendAmount),
                    receiveAmount = BigDecimal(quote.receiveAmount),
                    exchangeRate = BigDecimal(quote.exchangeRate),
                    feeAmount = BigDecimal(quote.feeAmount),
                    feeCurrency = quote.feeCurrency,
                    sendCurrency = quote.sendCurrency,
                    receiveCurrency = quote.receiveCurrency,
                )
            }
            cacheQuote(quoteId, result)
            return result
        } catch (e: QuoteExpiredException) {
            throw e
        } catch (e: StatusRuntimeException) {
            log.error("gRPC call to pricing-service failed: status={}, message={}", e.status.code, e.message)
            when (e.status.code) {
                Status.Code.INVALID_ARGUMENT, Status.Code.NOT_FOUND ->
                    throw QuoteExpiredException(quoteId, e.status.description)
                else ->
                    throw PricingUnavailableException("Pricing service error: ${e.status.code}", e)
            }
        } catch (e: CallNotPermittedException) {
            log.warn("Circuit breaker OPEN for pricing-service, attempting cache fallback for quoteId={}", quoteId)
            return getCachedQuote(quoteId)
                ?: throw PricingUnavailableException("Pricing service circuit breaker is open, no cached quote", e)
        } catch (e: PricingUnavailableException) {
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error calling pricing-service", e)
            throw PricingUnavailableException("Pricing service unavailable: ${e.message}", e)
        }
    }

    private fun cacheQuote(quoteId: String, quoteData: QuoteData) {
        try {
            val key = "$QUOTE_CACHE_PREFIX$quoteId"
            val json = objectMapper.writeValueAsString(quoteData)
            redisTemplate.opsForValue().set(key, json, QUOTE_CACHE_TTL)
            log.debug("Cached validated quote: quoteId={}", quoteId)
        } catch (e: Exception) {
            log.warn("Failed to cache quote quoteId={}: {}", quoteId, e.message)
        }
    }

    private fun getCachedQuote(quoteId: String): QuoteData? {
        return try {
            val key = "$QUOTE_CACHE_PREFIX$quoteId"
            val json = redisTemplate.opsForValue().get(key)
            if (json != null) {
                log.info("Cache fallback HIT for quoteId={}", quoteId)
                objectMapper.readValue(json, QuoteData::class.java)
            } else {
                log.warn("Cache fallback MISS for quoteId={}", quoteId)
                null
            }
        } catch (e: Exception) {
            log.warn("Cache fallback error for quoteId={}: {}", quoteId, e.message)
            null
        }
    }
}
