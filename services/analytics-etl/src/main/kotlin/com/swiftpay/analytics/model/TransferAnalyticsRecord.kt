package com.swiftpay.analytics.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TransferAnalyticsRecord(
    val transferId: UUID,
    val senderId: UUID,
    val sourceCountry: String,
    val destCountry: String,
    val corridor: String,
    val sendAmount: BigDecimal,
    val sendCurrency: String,
    val receiveAmount: BigDecimal,
    val receiveCurrency: String,
    val exchangeRate: BigDecimal,
    val feeAmount: BigDecimal,
    val feeCurrency: String,
    val deliveryMethod: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
