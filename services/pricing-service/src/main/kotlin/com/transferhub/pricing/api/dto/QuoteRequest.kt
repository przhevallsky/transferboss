package com.transferhub.pricing.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class QuoteRequest(
    val sourceCountry: String,
    val destinationCountry: String,
    val sendCurrency: String,
    val receiveCurrency: String,
    val sendAmount: String,
    val deliveryMethod: String,
    val senderId: String,
)
