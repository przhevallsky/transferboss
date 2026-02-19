package com.swiftpay.transfer.domain.vo

/** Статус события в outbox-таблице */
enum class OutboxEventStatus {
    PENDING,    // Ожидает отправки в Kafka
    SENT,       // Успешно отправлено
    FAILED      // Ошибка отправки (будет retry)
}
