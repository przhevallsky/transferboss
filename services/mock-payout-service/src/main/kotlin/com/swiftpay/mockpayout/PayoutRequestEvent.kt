package com.swiftpay.mockpayout

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class PayoutRequestEvent(
    @JsonProperty("event_id") val eventId: String,
    @JsonProperty("transfer_id") val transferId: String,
    @JsonProperty("recipient_id") val recipientId: String,
    @JsonProperty("receive_amount") val receiveAmount: String,
    @JsonProperty("receive_currency") val receiveCurrency: String,
    @JsonProperty("delivery_method") val deliveryMethod: String,
    @JsonProperty("idempotency_key") val idempotencyKey: String
)
