package com.swiftpay.transfer.client

import com.swiftpay.transfer.exception.PricingUnavailableException
import com.swiftpay.transfer.exception.QuoteExpiredException
import com.transferhub.pricing.grpc.v1.PricingServiceGrpc
import com.transferhub.pricing.grpc.v1.ValidateQuoteRequest
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory
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
class PricingClient(pricingChannel: ManagedChannel) {

    private val log = LoggerFactory.getLogger(PricingClient::class.java)

    private val stub = PricingServiceGrpc.newBlockingStub(pricingChannel)

    private val circuitBreaker = CircuitBreaker.of(
        "pricing-service",
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .build()
    )

    fun validateQuote(quoteId: String): QuoteData {
        try {
            return circuitBreaker.executeSupplier {
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
            log.warn("Circuit breaker open for pricing-service: {}", e.message)
            throw PricingUnavailableException("Pricing service circuit breaker is open", e)
        } catch (e: Exception) {
            log.error("Unexpected error calling pricing-service", e)
            throw PricingUnavailableException("Pricing service unavailable: ${e.message}", e)
        }
    }
}
