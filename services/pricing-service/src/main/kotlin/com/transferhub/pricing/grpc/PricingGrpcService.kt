package com.transferhub.pricing.grpc

import com.transferhub.pricing.grpc.v1.*
import org.slf4j.LoggerFactory
import java.util.UUID

class PricingGrpcService : PricingServiceGrpcKt.PricingServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(PricingGrpcService::class.java)

    override suspend fun getQuote(request: GetQuoteRequest): QuoteResponse {
        logger.info(
            "GetQuote request: {} {} -> {} {}, amount={}, method={}",
            request.sourceCountry, request.sendCurrency,
            request.destinationCountry, request.receiveCurrency,
            request.sendAmount, request.deliveryMethod
        )

        val quoteId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + 30_000

        return quoteResponse {
            this.quoteId = quoteId
            this.sendAmount = request.sendAmount
            this.receiveAmount = "4250.00"
            this.exchangeRate = "4.2500"
            this.feeAmount = "3.99"
            this.feeCurrency = request.sendCurrency
            this.sendCurrency = request.sendCurrency
            this.receiveCurrency = request.receiveCurrency
            this.deliveryMethod = request.deliveryMethod
            this.expiresAtEpochMs = expiresAt
            this.ttlSeconds = 30
        }
    }

    override suspend fun validateQuote(request: ValidateQuoteRequest): ValidateQuoteResponse {
        logger.info("ValidateQuote request: quoteId={}", request.quoteId)

        return validateQuoteResponse {
            this.isValid = true
            this.quote = quoteResponse {
                this.quoteId = request.quoteId
                this.sendAmount = "1000.00"
                this.receiveAmount = "4250.00"
                this.exchangeRate = "4.2500"
                this.feeAmount = "3.99"
                this.feeCurrency = "GBP"
                this.sendCurrency = "GBP"
                this.receiveCurrency = "PLN"
                this.deliveryMethod = "BANK_TRANSFER"
                this.expiresAtEpochMs = System.currentTimeMillis() + 25_000
                this.ttlSeconds = 25
            }
        }
    }
}
