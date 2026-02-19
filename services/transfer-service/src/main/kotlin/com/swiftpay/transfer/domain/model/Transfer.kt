package com.swiftpay.transfer.domain.model

import com.swiftpay.transfer.domain.vo.DeliveryMethod
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Основная сущность — международный денежный перевод.
 *
 * Маппится на таблицу `transfers` (Flyway V001).
 * Optimistic locking через @Version — защита от конкурентных обновлений статуса.
 */
@Entity
@Table(name = "transfers")
class Transfer(

    @Id
    @Column(name = "id", updatable = false)
    val id: UUID = UUID.randomUUID(),

    // --- Идемпотентность ---
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    val idempotencyKey: UUID,

    // --- Отправитель ---
    @Column(name = "sender_id", nullable = false, updatable = false)
    val senderId: UUID,

    // --- Котировка ---
    @Column(name = "quote_id", nullable = false, updatable = false)
    val quoteId: UUID,

    // --- Финансовые данные (immutable после создания) ---
    @Column(name = "send_amount", nullable = false, precision = 15, scale = 2)
    val sendAmount: BigDecimal,

    @Column(name = "send_currency", nullable = false, length = 3)
    val sendCurrency: String,

    @Column(name = "receive_amount", nullable = false, precision = 15, scale = 2)
    val receiveAmount: BigDecimal,

    @Column(name = "receive_currency", nullable = false, length = 3)
    val receiveCurrency: String,

    @Column(name = "exchange_rate", nullable = false, precision = 12, scale = 6)
    val exchangeRate: BigDecimal,

    @Column(name = "fee_amount", nullable = false, precision = 10, scale = 2)
    val feeAmount: BigDecimal,

    @Column(name = "fee_currency", nullable = false, length = 3)
    val feeCurrency: String,

    // --- Маршрут ---
    @Column(name = "source_country", nullable = false, length = 2)
    val sourceCountry: String,

    @Column(name = "dest_country", nullable = false, length = 2)
    val destCountry: String,

    @Column(name = "delivery_method", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    val deliveryMethod: DeliveryMethod,

    // --- Получатель ---
    @Column(name = "recipient_id", nullable = false)
    val recipientId: UUID,

    // --- Состояние (mutable) ---
    @Column(name = "status", nullable = false, length = 30)
    @Convert(converter = TransferStatusConverter::class)
    var status: TransferStatus = TransferStatus.Created,

    @Column(name = "status_reason")
    var statusReason: String? = null,

    // --- Saga tracking (заполняются позже, при получении событий) ---
    @Column(name = "payment_id")
    var paymentId: UUID? = null,

    @Column(name = "payout_id")
    var payoutId: UUID? = null,

    // --- Optimistic locking ---
    @Version
    @Column(name = "version", nullable = false)
    var version: Int = 0,

    // --- Аудит ---
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null

) {

    /**
     * Переход в новый статус с валидацией state machine.
     * Выбрасывает exception при недопустимом переходе.
     *
     * @param newStatus целевой статус
     * @param reason причина перехода (для FAILED, CANCELLED, COMPLIANCE_HOLD)
     * @throws IllegalStateException если переход из текущего статуса в целевой не допустим
     */
    fun transitionTo(newStatus: TransferStatus, reason: String? = null) {
        check(status.canTransitionTo(newStatus)) {
            "Invalid status transition: ${status.value} → ${newStatus.value}. " +
                "Allowed transitions from ${status.value}: ${status.allowedTransitions().map { it.value }}"
        }
        status = newStatus
        statusReason = reason
        updatedAt = Instant.now()

        if (newStatus.isTerminal()) {
            completedAt = Instant.now()
        }
    }

    /** Можно ли отменить перевод? Только из CREATED */
    fun isCancellable(): Boolean = status.canTransitionTo(TransferStatus.Cancelled)

    // --- equals / hashCode по id (JPA best practice) ---

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Transfer) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Transfer(id=$id, status=${status.value}, sendAmount=$sendAmount $sendCurrency → $receiveAmount $receiveCurrency)"
}
