package com.swiftpay.outbox.repository

import com.swiftpay.outbox.domain.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    @Query(
        value = """
            SELECT * FROM outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findPendingForUpdate(batchSize: Int): List<OutboxEvent>

    @Modifying
    @Query(
        """
        UPDATE OutboxEvent e
        SET e.status = 'SENT',
            e.processedAt = :processedAt,
            e.kafkaTopic = :topic,
            e.kafkaOffset = :offset
        WHERE e.id = :id
        """
    )
    fun markSent(id: UUID, processedAt: Instant, topic: String, offset: Long)

    @Modifying
    @Query(
        """
        UPDATE OutboxEvent e
        SET e.status = 'FAILED',
            e.processedAt = :processedAt
        WHERE e.id = :id
        """
    )
    fun markFailed(id: UUID, processedAt: Instant)
}
