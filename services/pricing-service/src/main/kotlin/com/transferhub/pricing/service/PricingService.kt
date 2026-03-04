package com.transferhub.pricing.service

import com.transferhub.pricing.api.dto.QuoteRequest
import com.transferhub.pricing.model.Quote
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

class PricingService(
    private val corridors: Map<String, CorridorPricing>,
    private val quoteCacheService: QuoteCacheService,
    private val quoteTtlSeconds: Long,
) {

    private val logger = LoggerFactory.getLogger(PricingService::class.java)

    suspend fun calculateQuote(request: QuoteRequest): Quote {
        val corridorKey = "${request.sourceCountry}_${request.destinationCountry}"
        val corridor = corridors[corridorKey]
            ?: throw CorridorNotSupportedException(request.sourceCountry, request.destinationCountry)

        val deliveryMethod = corridor.deliveryMethods.find { it.type == request.deliveryMethod }
            ?: throw DeliveryMethodNotAvailableException(request.deliveryMethod, corridorKey)

        val sendAmount = try {
            BigDecimal(request.sendAmount)
        } catch (e: NumberFormatException) {
            throw InvalidAmountException("Invalid send amount: ${request.sendAmount}")
        }

        if (sendAmount < corridor.minAmount) {
            throw InvalidAmountException(
                "Send amount ${sendAmount.toPlainString()} is below minimum ${corridor.minAmount.toPlainString()}"
            )
        }
        if (sendAmount > corridor.maxAmount) {
            throw InvalidAmountException(
                "Send amount ${sendAmount.toPlainString()} exceeds maximum ${corridor.maxAmount.toPlainString()}"
            )
        }
        if (sendAmount <= corridor.feeAmount) {
            throw InvalidAmountException(
                "Send amount ${sendAmount.toPlainString()} must be greater than fee ${corridor.feeAmount.toPlainString()}"
            )
        }

        val receiveAmount = (sendAmount - corridor.feeAmount)
            .multiply(corridor.exchangeRate)
            .setScale(2, RoundingMode.HALF_UP)

        val quote = Quote(
            quoteId = UUID.randomUUID().toString(),
            sourceCountry = corridor.sourceCountry,
            destinationCountry = corridor.destinationCountry,
            sendCurrency = corridor.sendCurrency,
            receiveCurrency = corridor.receiveCurrency,
            sendAmount = sendAmount,
            receiveAmount = receiveAmount,
            exchangeRate = corridor.exchangeRate,
            feeAmount = corridor.feeAmount,
            feeCurrency = corridor.sendCurrency,
            deliveryMethod = request.deliveryMethod,
            expiresAt = Instant.now().plusSeconds(quoteTtlSeconds),
            senderId = request.senderId,
            deliveryEstimateMinMinutes = deliveryMethod.estimateMinMinutes,
            deliveryEstimateMaxMinutes = deliveryMethod.estimateMaxMinutes,
        )

        quoteCacheService.save(quote)
        logger.info("Created quote {} for corridor {}", quote.quoteId, corridorKey)

        return quote
    }

    suspend fun validateQuote(quoteId: String): QuoteValidationResult {
        val quote = quoteCacheService.get(quoteId)
            ?: return QuoteValidationResult(isValid = false, quote = null)

        if (quote.expiresAt.isBefore(Instant.now())) {
            return QuoteValidationResult(isValid = false, quote = null)
        }

        return QuoteValidationResult(isValid = true, quote = quote)
    }
}

data class QuoteValidationResult(
    val isValid: Boolean,
    val quote: Quote?,
)

class CorridorNotSupportedException(sourceCountry: String, destinationCountry: String) :
    RuntimeException("Corridor ${sourceCountry}_${destinationCountry} is not supported")

class DeliveryMethodNotAvailableException(method: String, corridor: String) :
    RuntimeException("Delivery method $method is not available for corridor $corridor")

class InvalidAmountException(message: String) :
    RuntimeException(message)
