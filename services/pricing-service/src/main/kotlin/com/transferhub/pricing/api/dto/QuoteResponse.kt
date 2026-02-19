package com.transferhub.pricing.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class QuoteRestResponse(
    val quoteId: String,
    val sendAmount: String,
    val receiveAmount: String,
    val exchangeRate: String,
    val feeAmount: String,
    val feeCurrency: String,
    val sendCurrency: String,
    val receiveCurrency: String,
    val deliveryMethod: String,
    val expiresAtEpochMs: Long,
    val ttlSeconds: Int,
)
