package com.swiftpay.transfer.repository

import com.swiftpay.transfer.domain.model.ConsumedEvent
import org.springframework.data.jpa.repository.JpaRepository

interface ConsumedEventRepository : JpaRepository<ConsumedEvent, String> {
    fun existsByEventId(eventId: String): Boolean
}
