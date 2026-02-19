package com.swiftpay.transfer.domain.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Запись идемпотентности.
 *
 * Когда клиент отправляет POST /api/v1/transfers с X-Idempotency-Key,
 * мы сохраняем key + response. При повторном запросе с тем же key —
 * возвращаем сохранённый response без повторного создания перевода.
 *
 * Маппится на таблицу `idempotency_keys` (Flyway V004).
 * PK — сам key (UUID), не отдельный id.
 */
@Entity
@Table(name = "idempotency_keys")
class IdempotencyRecord(

    /** Idempotency key из заголовка X-Idempotency-Key — является PK */
    @Id
    @Column(name = "key", updatable = false)
    val key: UUID,

    /** ID созданного перевода */
    @Column(name = "transfer_id", nullable = false)
    val transferId: UUID,

    /** HTTP status code, который вернули при первом запросе */
    @Column(name = "response_status", nullable = false)
    val responseStatus: Int,

    /** Сериализованный JSON ответа (для возврата при повторном запросе) */
    @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
    val responseBody: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    /** Записи автоматически удаляются через 24 часа */
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant = Instant.now().plusSeconds(86400) // 24h

) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdempotencyRecord) return false
        return key == other.key
    }

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String =
        "IdempotencyRecord(key=$key, transferId=$transferId, responseStatus=$responseStatus)"
}
