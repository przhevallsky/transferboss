package com.swiftpay.transfer.repository

import com.swiftpay.transfer.domain.model.IdempotencyRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface IdempotencyKeyRepository : JpaRepository<IdempotencyRecord, UUID> {

    /**
     * Найти запись идемпотентности по ключу.
     * PK таблицы = key (UUID), поэтому findById() из JpaRepository тоже работает,
     * но этот метод явно выражает intent.
     */
    fun findByKey(key: UUID): IdempotencyRecord?

    /** Проверка существования */
    fun existsByKey(key: UUID): Boolean

    /**
     * Удаление expired записей (cleanup job, вызывается по расписанию).
     */
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Instant): Int
}
