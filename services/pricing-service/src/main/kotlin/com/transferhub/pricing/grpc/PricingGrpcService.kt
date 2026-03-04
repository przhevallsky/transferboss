package com.transferhub.pricing.grpc

import com.transferhub.pricing.api.dto.QuoteRequest
import com.transferhub.pricing.grpc.v1.*
import com.transferhub.pricing.model.Quote
import com.transferhub.pricing.service.CorridorNotSupportedException
import com.transferhub.pricing.service.DeliveryMethodNotAvailableException
import com.transferhub.pricing.service.InvalidAmountException
import com.transferhub.pricing.service.PricingService
import io.grpc.Status
import io.grpc.StatusException
import org.slf4j.LoggerFactory
import java.time.Instant

class PricingGrpcService(
    private val pricingService: PricingService
) : PricingServiceGrpcKt.PricingServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(PricingGrpcService::class.java)

    override suspend fun getQuote(request: GetQuoteRequest): QuoteResponse {
        logger.info(
            "GetQuote request: {} {} -> {} {}, amount={}, method={}",
            request.sourceCountry, request.sendCurrency,
            request.destinationCountry, request.receiveCurrency,
            request.sendAmount, request.deliveryMethod
        )

        try {
            val quoteRequest = QuoteRequest(
                sourceCountry = request.sourceCountry,
                destinationCountry = request.destinationCountry,
                sendCurrency = request.sendCurrency,
                receiveCurrency = request.receiveCurrency,
                sendAmount = request.sendAmount,
                deliveryMethod = request.deliveryMethod,
                senderId = request.senderId,
            )

            val quote = pricingService.calculateQuote(quoteRequest)
            return toQuoteResponse(quote)
        } catch (e: CorridorNotSupportedException) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(e.message))
        } catch (e: DeliveryMethodNotAvailableException) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(e.message))
        } catch (e: InvalidAmountException) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription(e.message))
        } catch (e: Exception) {
            logger.error("Unexpected error in getQuote", e)
            throw StatusException(Status.INTERNAL.withDescription("Internal error"))
        }
    }

    override suspend fun validateQuote(request: ValidateQuoteRequest): ValidateQuoteResponse {
        logger.info("ValidateQuote request: quoteId={}", request.quoteId)

        if (request.quoteId.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("quoteId must not be blank"))
        }

        try {
            val result = pricingService.validateQuote(request.quoteId)

            return validateQuoteResponse {
                this.isValid = result.isValid
                if (result.isValid && result.quote != null) {
                    this.quote = toQuoteResponse(result.quote)
                }
                if (!result.isValid) {
                    this.rejectionReason = "Quote not found or expired"
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error in validateQuote", e)
            throw StatusException(Status.INTERNAL.withDescription("Internal error"))
        }
    }

    private fun toQuoteResponse(quote: Quote): QuoteResponse {
        val now = Instant.now()
        val ttl = (quote.expiresAt.epochSecond - now.epochSecond).toInt().coerceAtLeast(0)

        return quoteResponse {
            this.quoteId = quote.quoteId
            this.sendAmount = quote.sendAmount.toPlainString()
            this.receiveAmount = quote.receiveAmount.toPlainString()
            this.exchangeRate = quote.exchangeRate.toPlainString()
            this.feeAmount = quote.feeAmount.toPlainString()
            this.feeCurrency = quote.feeCurrency
            this.sendCurrency = quote.sendCurrency
            this.receiveCurrency = quote.receiveCurrency
            this.deliveryMethod = quote.deliveryMethod
            this.expiresAtEpochMs = quote.expiresAt.toEpochMilli()
            this.ttlSeconds = ttl
        }
    }
}
