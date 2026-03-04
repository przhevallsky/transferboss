package com.swiftpay.transfer.domain.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "consumed_events")
class ConsumedEvent(

    @Id
    @Column(name = "event_id", updatable = false, length = 128)
    val eventId: String,

    @Column(name = "consumer_group", nullable = false, length = 128)
    val consumerGroup: String,

    @Column(name = "topic", nullable = false, length = 256)
    val topic: String,

    @Column(name = "processed_at", nullable = false, updatable = false)
    val processedAt: Instant = Instant.now()

) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConsumedEvent) return false
        return eventId == other.eventId
    }

    override fun hashCode(): Int = eventId.hashCode()

    override fun toString(): String =
        "ConsumedEvent(eventId=$eventId, consumerGroup=$consumerGroup, topic=$topic)"
}
