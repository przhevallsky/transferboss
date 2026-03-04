package com.swiftpay.transfer.repository

import com.swiftpay.transfer.domain.model.Recipient
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RecipientRepository : JpaRepository<Recipient, UUID> {

    fun findRecipientById(id: UUID): Recipient?
}
