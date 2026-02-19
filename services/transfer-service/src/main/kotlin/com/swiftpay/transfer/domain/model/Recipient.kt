package com.swiftpay.transfer.domain.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Получатель перевода.
 *
 * Хранит данные, необходимые для выплаты.
 * delivery_details — JSONB, т.к. разные delivery methods имеют разный набор полей:
 * - bank_deposit: {"bank_name": "BDO", "account_number": "123", "branch_code": "001"}
 * - mobile_wallet: {"provider": "GCASH", "phone_number": "+639171234567"}
 * - cash_pickup: {"pickup_network": "CEBUANA", "id_type": "PASSPORT"}
 *
 * Маппится на таблицу `recipients` (Flyway V002).
 */
@Entity
@Table(name = "recipients")
class Recipient(

    @Id
    @Column(name = "id", updatable = false)
    val id: UUID = UUID.randomUUID(),

    /** Владелец (отправитель, который добавил получателя) */
    @Column(name = "sender_id", nullable = false)
    val senderId: UUID,

    @Column(name = "first_name", nullable = false, length = 100)
    var firstName: String,

    @Column(name = "last_name", nullable = false, length = 100)
    var lastName: String,

    /** Страна получателя (ISO 3166-1 alpha-2) */
    @Column(name = "country", nullable = false, columnDefinition = "char(2)")
    val country: String,

    /** Реквизиты доставки — JSONB, структура зависит от delivery method */
    @Column(name = "delivery_details", nullable = false, columnDefinition = "jsonb")
    var deliveryDetails: String,

    // --- Аудит ---
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    /** Мягкое удаление */
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Recipient) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Recipient(id=$id, name=$firstName $lastName, country=$country)"
}
