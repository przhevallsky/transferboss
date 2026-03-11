package com.swiftpay.mockpayment

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentRequestEvent(
    @JsonProperty("event_id") val eventId: String,
    @JsonProperty("transfer_id") val transferId: String,
    @JsonProperty("sender_id") val senderId: String,
    @JsonProperty("send_amount") val sendAmount: String,
    @JsonProperty("send_currency") val sendCurrency: String,
    @JsonProperty("idempotency_key") val idempotencyKey: String
)
