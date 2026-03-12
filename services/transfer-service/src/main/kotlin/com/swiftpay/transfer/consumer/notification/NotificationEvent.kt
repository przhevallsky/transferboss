package com.swiftpay.transfer.consumer.notification

import com.fasterxml.jackson.annotation.JsonProperty

data class NotificationEvent(
    @JsonProperty("transfer_id") val transferId: String,
    @JsonProperty("sender_id") val senderId: String,
    @JsonProperty("event_type") val eventType: String,
    @JsonProperty("notification_text") val notificationText: String,
    val channels: List<String>
)
