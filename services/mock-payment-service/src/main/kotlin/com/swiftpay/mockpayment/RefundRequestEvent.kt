package com.swiftpay.mockpayment

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class RefundRequestEvent(
    @JsonProperty("event_id") val eventId: String,
    @JsonProperty("transfer_id") val transferId: String,
    @JsonProperty("payment_id") val paymentId: String,
    @JsonProperty("refund_amount") val refundAmount: String,
    @JsonProperty("refund_currency") val refundCurrency: String,
    @JsonProperty("idempotency_key") val idempotencyKey: String
)
