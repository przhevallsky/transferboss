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
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Money transfer operations")
class TransferController(
    private val transferService: TransferService,
    private val recipientRepository: RecipientRepository,
    private val transferCacheService: TransferCacheService
) {
    private val log = LoggerFactory.getLogger(TransferController::class.java)

    @Operation(
        summary = "Create a new transfer",
        description = "Creates a money transfer. Requires X-Idempotency-Key header for duplicate protection."
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Transfer created"),
        ApiResponse(responseCode = "200", description = "Idempotent request — returning cached result"),
        ApiResponse(responseCode = "400", description = "Validation error",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))]),
        ApiResponse(responseCode = "422", description = "Business rule violation",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))])
    )
    @PostMapping
    fun createTransfer(
        @Valid @RequestBody request: CreateTransferRequest,
        @Parameter(description = "Unique idempotency key (UUID)", required = true)
        @RequestHeader("X-Idempotency-Key") idempotencyKey: UUID,
        @Parameter(description = "Sender ID (temporary, will be from JWT)", required = false)
        @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?
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

    @Operation(summary = "Get transfer by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Transfer found"),
        ApiResponse(responseCode = "404", description = "Transfer not found",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))])
    )
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

    @Operation(summary = "List transfers with cursor-based pagination")
    @GetMapping
    fun listTransfers(
        @Parameter(description = "Sender ID")
        @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?,
        @Parameter(description = "Opaque cursor from previous response")
        @RequestParam(required = false) cursor: String?,
        @Parameter(description = "Page size (1-100, default 20)")
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
