package com.swiftpay.transfer.domain.model

/**
 * State machine перевода. Sealed class гарантирует:
 * 1. Exhaustive when — компилятор проверяет, что все статусы обработаны
 * 2. Допустимые переходы зашиты в модель — невозможно перейти из COMPLETED в CREATED
 *
 * Жизненный цикл (happy path):
 * CREATED → COMPLIANCE_CHECK → PAYMENT_PENDING → PAYMENT_CAPTURED →
 * → PAYOUT_PENDING → DELIVERING → COMPLETED
 *
 * Ошибочные пути: PAYMENT_FAILED, FAILED → REFUND_PENDING → REFUNDED, COMPLIANCE_REJECTED, CANCELLED
 *
 * Значения совпадают с CHECK constraint в V001__create_transfers_table.sql.
 */
sealed class TransferStatus(val value: String) {

    // --- Happy path ---
    data object Created : TransferStatus("CREATED")
    data object ComplianceCheck : TransferStatus("COMPLIANCE_CHECK")
    data object ComplianceHold : TransferStatus("COMPLIANCE_HOLD")
    data object PaymentPending : TransferStatus("PAYMENT_PENDING")
    data object PaymentCaptured : TransferStatus("PAYMENT_CAPTURED")
    data object PaymentFailed : TransferStatus("PAYMENT_FAILED")
    data object PayoutPending : TransferStatus("PAYOUT_PENDING")
    data object Delivering : TransferStatus("DELIVERING")
    data object Completed : TransferStatus("COMPLETED")

    // --- Error / Compensation ---
    data object Failed : TransferStatus("FAILED")
    data object ComplianceRejected : TransferStatus("COMPLIANCE_REJECTED")
    data object Cancelled : TransferStatus("CANCELLED")
    data object RefundPending : TransferStatus("REFUND_PENDING")
    data object Refunded : TransferStatus("REFUNDED")

    /**
     * Допустимые переходы из текущего статуса.
     * Если статус терминальный (COMPLETED, REFUNDED, etc.) — пустой set.
     */
    fun allowedTransitions(): Set<TransferStatus> = when (this) {
        Created -> setOf(ComplianceCheck, Cancelled)
        ComplianceCheck -> setOf(ComplianceHold, PaymentPending, ComplianceRejected)
        ComplianceHold -> setOf(PaymentPending, ComplianceRejected)
        PaymentPending -> setOf(PaymentCaptured, PaymentFailed)
        PaymentCaptured -> setOf(PayoutPending)
        PayoutPending -> setOf(Delivering, Failed)
        Delivering -> setOf(Completed, Failed)
        Failed -> setOf(RefundPending)
        RefundPending -> setOf(Refunded)

        // Terminal states — нет допустимых переходов
        Completed -> emptySet()
        PaymentFailed -> emptySet()
        ComplianceRejected -> emptySet()
        Cancelled -> emptySet()
        Refunded -> emptySet()
    }

    /** Можно ли перейти в указанный статус? */
    fun canTransitionTo(target: TransferStatus): Boolean =
        target in allowedTransitions()

    /** Является ли статус терминальным (финальным)? */
    fun isTerminal(): Boolean = allowedTransitions().isEmpty()

    /** Нужно ли показывать клиенту как "Processing" (скрытые внутренние статусы) */
    fun displayStatus(): String = when (this) {
        ComplianceCheck -> "PROCESSING"
        ComplianceHold -> "UNDER_REVIEW"
        PaymentPending -> "PROCESSING"
        PaymentCaptured -> "PROCESSING"
        PayoutPending -> "PROCESSING"
        Delivering -> "IN_TRANSIT"
        RefundPending -> "REFUNDING"
        else -> value
    }

    companion object {
        /** Парсинг из строки БД */
        fun fromString(value: String): TransferStatus = when (value) {
            "CREATED" -> Created
            "COMPLIANCE_CHECK" -> ComplianceCheck
            "COMPLIANCE_HOLD" -> ComplianceHold
            "PAYMENT_PENDING" -> PaymentPending
            "PAYMENT_CAPTURED" -> PaymentCaptured
            "PAYMENT_FAILED" -> PaymentFailed
            "PAYOUT_PENDING" -> PayoutPending
            "DELIVERING" -> Delivering
            "COMPLETED" -> Completed
            "FAILED" -> Failed
            "COMPLIANCE_REJECTED" -> ComplianceRejected
            "CANCELLED" -> Cancelled
            "REFUND_PENDING" -> RefundPending
            "REFUNDED" -> Refunded
            else -> throw IllegalArgumentException("Unknown transfer status: $value")
        }

        /** Все возможные статусы (для валидации, тестов) */
        val ALL: List<TransferStatus> = listOf(
            Created, ComplianceCheck, ComplianceHold, PaymentPending,
            PaymentCaptured, PaymentFailed, PayoutPending, Delivering,
            Completed, Failed, ComplianceRejected, Cancelled, RefundPending, Refunded
        )
    }

    override fun toString(): String = value
}
