package com.transferhub.pricing.model

import java.math.BigDecimal
import java.time.Instant

data class Quote(
    val quoteId: String,
    val sourceCountry: String,
    val destinationCountry: String,
    val sendCurrency: String,
    val receiveCurrency: String,
    val sendAmount: BigDecimal,
    val receiveAmount: BigDecimal,
    val exchangeRate: BigDecimal,
    val feeAmount: BigDecimal,
    val feeCurrency: String,
    val deliveryMethod: String,
    val expiresAt: Instant,
)
