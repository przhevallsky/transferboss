package com.swiftpay.outbox.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "outbox")
class OutboxEvent(

    @Id
    @Column(name = "id", updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "entity_type", nullable = false, length = 50)
    val entityType: String = "TRANSFER",

    @Column(name = "entity_id", nullable = false)
    val entityId: UUID,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    val payload: String,

    @Column(name = "status", nullable = false, length = 20)
    var status: String = "PENDING",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Column(name = "kafka_topic", length = 200)
    var kafkaTopic: String? = null,

    @Column(name = "kafka_offset")
    var kafkaOffset: Long? = null
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEvent) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "OutboxEvent(id=$id, type=$eventType, entityId=$entityId, status=$status)"
}
