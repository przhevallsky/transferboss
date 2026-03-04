package com.swiftpay.transfer.repository

import com.swiftpay.transfer.domain.model.IdempotencyRecord
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IdempotencyKeyRepository : JpaRepository<IdempotencyRecord, UUID>
