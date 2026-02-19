package com.swiftpay.transfer.repository

import com.swiftpay.transfer.domain.model.Recipient
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RecipientRepository : JpaRepository<Recipient, UUID> {

    /** Найти получателя по ID */
    fun findRecipientById(id: UUID): Recipient?

    /** Все активные получатели конкретного отправителя */
    fun findBySenderIdAndIsActiveTrueOrderByCreatedAtDesc(senderId: UUID): List<Recipient>

    /** Проверка принадлежности: получатель принадлежит данному отправителю */
    fun existsByIdAndSenderId(id: UUID, senderId: UUID): Boolean
}
