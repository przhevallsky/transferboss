package com.swiftpay.transfer.repository

import com.swiftpay.transfer.domain.model.Transfer
import com.swiftpay.transfer.domain.model.TransferStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TransferRepository : JpaRepository<Transfer, UUID> {

    /** Найти перевод по ID (Kotlin nullable) */
    fun findTransferById(id: UUID): Transfer?

    /** Все переводы конкретного отправителя */
    fun findBySenderIdOrderByCreatedAtDesc(senderId: UUID): List<Transfer>

    /** Проверить существование по idempotency key */
    fun existsByIdempotencyKey(idempotencyKey: UUID): Boolean

    /** Найти по idempotency key (для возврата cached response) */
    fun findByIdempotencyKey(idempotencyKey: UUID): Transfer?

    /**
     * Cursor-based pagination: первая страница (без курсора).
     *
     * Возвращает size+1 записей — если пришло size+1, значит есть следующая страница.
     * Использует index: idx_transfers_sender_created (sender_id, created_at DESC)
     */
    @Query("""
        SELECT t FROM Transfer t
        WHERE t.senderId = :senderId
        ORDER BY t.createdAt DESC, t.id DESC
    """)
    fun findBySenderIdFirstPage(
        @Param("senderId") senderId: UUID,
        limit: Pageable
    ): List<Transfer>

    /**
     * Cursor-based pagination: последующие страницы (с курсором).
     *
     * Cursor = (createdAt, id) последнего элемента предыдущей страницы.
     * PostgreSQL row-value comparison через native query для надёжности.
     */
    @Query(
        value = """
            SELECT * FROM transfers t
            WHERE t.sender_id = :senderId
              AND (t.created_at, t.id) < (:cursorCreatedAt, CAST(:cursorId AS uuid))
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT :limit
        """,
        nativeQuery = true
    )
    fun findBySenderIdAfterCursor(
        @Param("senderId") senderId: UUID,
        @Param("cursorCreatedAt") cursorCreatedAt: Instant,
        @Param("cursorId") cursorId: UUID,
        @Param("limit") limit: Int
    ): List<Transfer>

    /** Подсчёт активных переводов пользователя (для лимитов) */
    fun countBySenderIdAndStatusNotIn(senderId: UUID, statuses: List<TransferStatus>): Long
}
