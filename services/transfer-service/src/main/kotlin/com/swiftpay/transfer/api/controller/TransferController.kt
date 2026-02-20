package com.swiftpay.transfer.api.controller

import com.swiftpay.transfer.api.dto.request.CreateTransferRequest
import com.swiftpay.transfer.api.dto.response.PaginatedResponse
import com.swiftpay.transfer.api.dto.response.PaginationMeta
import com.swiftpay.transfer.api.dto.response.TransferResponse
import com.swiftpay.transfer.api.mapper.TransferMapper.toCommand
import com.swiftpay.transfer.api.mapper.TransferMapper.toResponse
import com.swiftpay.transfer.repository.RecipientRepository
import com.swiftpay.transfer.service.TransferCacheService
import com.swiftpay.transfer.service.TransferService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController(
    private val transferService: TransferService,
    private val recipientRepository: RecipientRepository,
    private val transferCacheService: TransferCacheService
) {
    private val log = LoggerFactory.getLogger(TransferController::class.java)

    /**
     * POST /api/v1/transfers — создание перевода.
     *
     * Headers:
     *   X-Idempotency-Key: UUID (required) — защита от дублирования
     *
     * Returns:
     *   201 Created + Location header — перевод создан
     *   200 OK — idempotency hit (тот же ключ, возвращаем cached result)
     *   400 — validation error
     *   422 — business rule violation
     */
    @PostMapping
    fun createTransfer(
        @Valid @RequestBody request: CreateTransferRequest,
        @RequestHeader("X-Idempotency-Key") idempotencyKey: UUID,
        @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?
        // TODO Sprint 5: заменить X-Sender-Id на извлечение из JWT token
    ): ResponseEntity<TransferResponse> {

        val senderId = senderIdHeader ?: UUID.fromString("00000000-0000-0000-0000-000000000001")

        val command = request.toCommand(senderId = senderId, idempotencyKey = idempotencyKey)
        val (transfer, isNew) = transferService.createTransfer(command)

        val recipient = recipientRepository.findRecipientById(transfer.recipientId)
        val response = transfer.toResponse(recipient)

        return if (isNew) {
            log.info("Transfer created: id={}", transfer.id)
            ResponseEntity
                .created(URI.create("/api/v1/transfers/${transfer.id}"))
                .body(response)
        } else {
            log.info("Idempotency hit: key={}, transferId={}", idempotencyKey, transfer.id)
            ResponseEntity.ok(response)
        }
    }

    /**
     * GET /api/v1/transfers/{id} — получить перевод по ID.
     * Cache-Aside: Redis → PostgreSQL → Redis.
     */
    @GetMapping("/{id}")
    fun getTransfer(@PathVariable id: UUID): ResponseEntity<TransferResponse> {

        val cached = transferCacheService.getCached(id)
        if (cached != null) {
            return ResponseEntity.ok(cached)
        }

        val transfer = transferService.getTransfer(id)
        val recipient = recipientRepository.findRecipientById(transfer.recipientId)
        val response = transfer.toResponse(recipient)

        transferCacheService.put(id, response)

        return ResponseEntity.ok(response)
    }

    /**
     * GET /api/v1/transfers — список переводов с cursor-based pagination.
     */
    @GetMapping
    fun listTransfers(
        @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<PaginatedResponse<TransferResponse>> {

        if (limit < 1 || limit > 100) {
            throw IllegalArgumentException("limit must be between 1 and 100, got: $limit")
        }

        val senderId = senderIdHeader ?: UUID.fromString("00000000-0000-0000-0000-000000000001")
        val (transfers, nextCursor) = transferService.listTransfers(senderId, cursor, limit)

        val items = transfers.map { transfer ->
            val recipient = recipientRepository.findRecipientById(transfer.recipientId)
            transfer.toResponse(recipient)
        }

        return ResponseEntity.ok(
            PaginatedResponse(
                items = items,
                pagination = PaginationMeta(
                    nextCursor = nextCursor,
                    hasMore = nextCursor != null
                )
            )
        )
    }
}
