package com.swiftpay.transfer.repository

import com.swiftpay.transfer.domain.model.OutboxEvent
import com.swiftpay.transfer.domain.vo.OutboxEventStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    /**
     * Найти события по entity ID и статусу.
     * Для проверки — что событие записалось в outbox при создании перевода.
     */
    fun findByEntityIdAndStatus(entityId: UUID, status: OutboxEventStatus): List<OutboxEvent>

    /** Все события конкретного перевода (для отладки и тестов) */
    fun findByEntityIdOrderByCreatedAtAsc(entityId: UUID): List<OutboxEvent>
}
