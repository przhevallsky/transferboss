package com.swiftpay.transfer.domain.vo

/** Типы событий, которые публикует Transfer Service */
enum class OutboxEventType {
    TRANSFER_CREATED,
    TRANSFER_STATUS_CHANGED,
    PAYMENT_REQUESTED,
    COMPLIANCE_REQUESTED,
    PAYOUT_REQUESTED,
    REFUND_REQUESTED
}
