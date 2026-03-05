package com.swiftpay.transfer.consumer

data class PayoutEvent(
    val eventId: String,
    val transferId: String,
    val eventType: String,
    val payoutId: String? = null,
    val reason: String? = null,
    val timestamp: String
)
