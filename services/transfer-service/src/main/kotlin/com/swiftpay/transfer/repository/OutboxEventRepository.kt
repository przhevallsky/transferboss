package com.swiftpay.transfer.repository

import com.swiftpay.transfer.domain.model.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    fun findByEntityIdOrderByCreatedAtAsc(entityId: UUID): List<OutboxEvent>
}
