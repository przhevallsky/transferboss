package com.swiftpay.transfer.consumer

data class PaymentEvent(
    val eventId: String,
    val transferId: String,
    val eventType: String,
    val paymentId: String? = null,
    val reason: String? = null,
    val timestamp: String
)
