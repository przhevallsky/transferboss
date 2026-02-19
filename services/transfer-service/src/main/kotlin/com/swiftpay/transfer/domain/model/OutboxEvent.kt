package com.swiftpay.transfer.domain.model

import com.swiftpay.transfer.domain.vo.OutboxEventStatus
import com.swiftpay.transfer.domain.vo.OutboxEventType
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Событие в outbox-таблице (Transactional Outbox Pattern).
 *
 * Записывается в ОДНОЙ транзакции с бизнес-данными (Transfer).
 * Outbox Service поллит эту таблицу и отправляет события в Kafka.
 *
 * Маппится на таблицу `outbox` (Flyway V003).
 */
@Entity
@Table(name = "outbox")
class OutboxEvent(

    @Id
    @Column(name = "id", updatable = false)
    val id: UUID = UUID.randomUUID(),

    /** Тип агрегата, к которому относится событие */
    @Column(name = "entity_type", nullable = false, length = 50)
    val entityType: String = "TRANSFER",

    /** ID агрегата (transfer_id) — используется как Kafka key для ordering */
    @Column(name = "entity_id", nullable = false)
    val entityId: UUID,

    /** Тип события */
    @Column(name = "event_type", nullable = false, length = 100)
    @Enumerated(EnumType.STRING)
    val eventType: OutboxEventType,

    /** JSON payload события (JSONB в PostgreSQL) */
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    val payload: String,

    /** Статус обработки */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: OutboxEventStatus = OutboxEventStatus.PENDING,

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
        "OutboxEvent(id=$id, type=${eventType.name}, entityId=$entityId, status=${status.name})"
}
