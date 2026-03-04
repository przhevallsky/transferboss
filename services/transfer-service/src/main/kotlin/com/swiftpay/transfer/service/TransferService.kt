package com.swiftpay.transfer.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.transfer.client.PricingClient
import com.swiftpay.transfer.domain.model.*
import com.swiftpay.transfer.domain.vo.DeliveryMethod
import com.swiftpay.transfer.domain.vo.OutboxEventStatus
import com.swiftpay.transfer.domain.vo.OutboxEventType
import com.swiftpay.transfer.exception.*
import com.swiftpay.transfer.lock.DistributedLockService
import com.swiftpay.transfer.repository.OutboxEventRepository
import com.swiftpay.transfer.repository.RecipientRepository
import com.swiftpay.transfer.repository.TransferRepository
import com.swiftpay.transfer.service.dto.CreateTransferCommand
import com.swiftpay.transfer.service.dto.TransferWithRecipient
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class TransferService(
    private val transferRepository: TransferRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val recipientRepository: RecipientRepository,
    private val objectMapper: ObjectMapper,
    private val distributedLockService: DistributedLockService,
    private val pricingClient: PricingClient
) {
    private val log = LoggerFactory.getLogger(TransferService::class.java)

    // --- Поддерживаемые коридоры (MVP: hardcoded, в будущем — из MongoDB/config) ---
    private val supportedCorridors: Map<String, Set<DeliveryMethod>> = mapOf(
        "US_PH" to setOf(DeliveryMethod.BANK_DEPOSIT, DeliveryMethod.CASH_PICKUP, DeliveryMethod.MOBILE_WALLET),
        "US_MX" to setOf(DeliveryMethod.BANK_DEPOSIT, DeliveryMethod.CASH_PICKUP),
        "GB_IN" to setOf(DeliveryMethod.BANK_DEPOSIT, DeliveryMethod.MOBILE_WALLET),
        "US_IN" to setOf(DeliveryMethod.BANK_DEPOSIT, DeliveryMethod.MOBILE_WALLET),
    )

    // --- Минимальные суммы по коридору ---
    private val minimumAmounts: Map<String, BigDecimal> = mapOf(
        "US_PH" to BigDecimal("10.00"),
        "US_MX" to BigDecimal("10.00"),
        "GB_IN" to BigDecimal("5.00"),
        "US_IN" to BigDecimal("10.00"),
    )

    /**
     * Создание перевода.
     *
     * КРИТИЧЕСКИ ВАЖНО: Transfer + OutboxEvent сохраняются в ОДНОЙ транзакции.
     * Если transaction commit прошёл — оба записаны. Если rollback — ни один.
     * Это гарантия Outbox Pattern: событие будет опубликовано в Kafka тогда и только тогда,
     * когда бизнес-данные записаны в БД.
     *
     * @return Pair<TransferWithRecipient, Boolean> — (перевод + получатель, isNew). isNew=false если idempotency hit.
     */
    @Transactional
    fun createTransfer(command: CreateTransferCommand): Pair<TransferWithRecipient, Boolean> {
        val lockKey = "sender/${command.senderId}/create"

        return distributedLockService.executeWithLock(lockKey) {
            // 1. IDEMPOTENCY CHECK: если ключ уже обработан — вернуть существующий перевод
            val existingTransfer = transferRepository.findByIdempotencyKey(command.idempotencyKey)
            if (existingTransfer != null) {
                log.info("Idempotency hit: key=${command.idempotencyKey}, transferId=${existingTransfer.id}")
                val recipient = recipientRepository.findRecipientById(existingTransfer.recipientId)
                return@executeWithLock Pair(TransferWithRecipient(existingTransfer, recipient), false)
            }

            // 2. BUSINESS VALIDATION
            validateTransfer(command)

            // 3. LOOKUP RECIPIENT (проверяем существование и принадлежность отправителю)
            val recipient = recipientRepository.findRecipientById(command.recipientId)
                ?: throw RecipientNotFoundException(command.recipientId)

            if (recipient.senderId != command.senderId) {
                throw RecipientNotFoundException(command.recipientId) // не раскрываем чужие данные
            }

            // 4. RESOLVE DELIVERY METHOD
            val deliveryMethod = DeliveryMethod.fromString(command.deliveryMethod)

            // 5. VALIDATE QUOTE via Pricing Service (gRPC)
            val quoteData = pricingClient.validateQuote(command.quoteId.toString())

            // 5a. Validate currency consistency between quote and request
            if (quoteData.sendCurrency != command.sendCurrency || quoteData.receiveCurrency != command.receiveCurrency) {
                throw QuoteCorridorMismatchException(
                    quoteId = command.quoteId.toString(),
                    quoteCurrency = "${quoteData.sendCurrency}→${quoteData.receiveCurrency}",
                    requestCurrency = "${command.sendCurrency}→${command.receiveCurrency}"
                )
            }

            // 6. CREATE TRANSFER ENTITY with validated quote data
            val transfer = Transfer(
                idempotencyKey = command.idempotencyKey,
                senderId = command.senderId,
                quoteId = command.quoteId,
                sendAmount = quoteData.sendAmount,
                sendCurrency = command.sendCurrency,
                receiveAmount = quoteData.receiveAmount,
                receiveCurrency = command.receiveCurrency,
                exchangeRate = quoteData.exchangeRate,
                feeAmount = quoteData.feeAmount,
                feeCurrency = quoteData.feeCurrency,
                sourceCountry = command.sourceCountry,
                destCountry = command.destCountry,
                deliveryMethod = deliveryMethod,
                recipientId = command.recipientId,
                status = TransferStatus.Created
            )

            // 7. CREATE OUTBOX EVENT (в той же транзакции!)
            val outboxPayload = buildTransferCreatedPayload(transfer, recipient)
            val outboxEvent = OutboxEvent(
                entityId = transfer.id,
                entityType = "TRANSFER",
                eventType = OutboxEventType.TRANSFER_CREATED,
                payload = outboxPayload,
                status = OutboxEventStatus.PENDING
            )

            // 8. SAVE BOTH в одной транзакции (@Transactional на методе)
            val savedTransfer = transferRepository.save(transfer)
            outboxEventRepository.save(outboxEvent)

            log.info(
                "Transfer created: id={}, sender={}, corridor={}→{}, amount={} {}, idempotencyKey={}",
                savedTransfer.id, savedTransfer.senderId,
                savedTransfer.sourceCountry, savedTransfer.destCountry,
                savedTransfer.sendAmount, savedTransfer.sendCurrency,
                savedTransfer.idempotencyKey
            )

            Pair(TransferWithRecipient(savedTransfer, recipient), true)
        }
    }

    @Transactional(readOnly = true)
    fun getTransfer(transferId: UUID): TransferWithRecipient {
        val transfer = transferRepository.findTransferById(transferId)
            ?: throw TransferNotFoundException(transferId)
        val recipient = recipientRepository.findRecipientById(transfer.recipientId)
        return TransferWithRecipient(transfer, recipient)
    }

    @Transactional
    fun transitionStatus(transferId: UUID, newStatus: TransferStatus, reason: String? = null): TransferWithRecipient {
        val lockKey = "transfer/${transferId}/status"

        return distributedLockService.executeWithLock(lockKey) {
            val transfer = transferRepository.findTransferById(transferId)
                ?: throw TransferNotFoundException(transferId)

            transfer.transitionTo(newStatus, reason)
            val saved = transferRepository.save(transfer)

            val recipient = recipientRepository.findRecipientById(saved.recipientId)
            TransferWithRecipient(saved, recipient)
        }
    }

    /**
     * Cursor-based pagination списка переводов.
     *
     * @param senderId фильтр по отправителю
     * @param cursor opaque cursor (Base64 encoded JSON), null для первой страницы
     * @param size размер страницы (default 20, max 100)
     * @return Pair<List<TransferWithRecipient>, String?> — (результаты, nextCursor или null если больше нет)
     */
    @Transactional(readOnly = true)
    fun listTransfers(
        senderId: UUID,
        cursor: String?,
        size: Int
    ): Pair<List<TransferWithRecipient>, String?> {

        val effectiveSize = size.coerceIn(1, 100)

        val transfers = if (cursor == null) {
            transferRepository.findBySenderIdFirstPage(
                senderId = senderId,
                limit = PageRequest.of(0, effectiveSize + 1)
            )
        } else {
            val (cursorCreatedAt, cursorId) = decodeCursor(cursor)
            transferRepository.findBySenderIdAfterCursor(
                senderId = senderId,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
                limit = effectiveSize + 1
            )
        }

        val hasMore = transfers.size > effectiveSize
        val page = if (hasMore) transfers.take(effectiveSize) else transfers

        val nextCursor = if (hasMore && page.isNotEmpty()) {
            val lastItem = page.last()
            encodeCursor(lastItem.createdAt, lastItem.id)
        } else {
            null
        }

        // Batch lookup recipients
        val recipientIds = page.map { it.recipientId }.distinct()
        val recipientMap = recipientRepository.findAllById(recipientIds).associateBy { it.id }
        val results = page.map { TransferWithRecipient(it, recipientMap[it.recipientId]) }

        return Pair(results, nextCursor)
    }

    // ---- Private helpers ----

    private fun validateTransfer(command: CreateTransferCommand) {
        val corridorId = "${command.sourceCountry}_${command.destCountry}"
        val allowedMethods = supportedCorridors[corridorId]
            ?: throw UnsupportedCorridorException(command.sourceCountry, command.destCountry)

        val deliveryMethod = DeliveryMethod.fromString(command.deliveryMethod)
        if (deliveryMethod !in allowedMethods) {
            throw UnsupportedDeliveryMethodException(
                deliveryMethod = deliveryMethod.name,
                corridorId = corridorId,
                availableMethods = allowedMethods.map { it.name }
            )
        }

        val minAmount = minimumAmounts[corridorId] ?: BigDecimal("1.00")
        if (command.sendAmount < minAmount) {
            throw MinimumAmountException(
                corridorId = corridorId,
                minAmount = minAmount,
                currency = command.sendCurrency,
                requestedAmount = command.sendAmount
            )
        }
    }

    /**
     * Формирование JSON payload для outbox event.
     * Этот JSON будет отправлен в Kafka как тело события transfer.created.
     */
    private fun buildTransferCreatedPayload(transfer: Transfer, recipient: Recipient): String {
        val payload = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "transfer_id" to transfer.id.toString(),
            "sender_id" to transfer.senderId.toString(),
            "send_amount" to transfer.sendAmount.toPlainString(),
            "send_currency" to transfer.sendCurrency,
            "receive_amount" to transfer.receiveAmount.toPlainString(),
            "receive_currency" to transfer.receiveCurrency,
            "exchange_rate" to transfer.exchangeRate.toPlainString(),
            "fee_amount" to transfer.feeAmount.toPlainString(),
            "delivery_method" to transfer.deliveryMethod.name,
            "source_country" to transfer.sourceCountry,
            "dest_country" to transfer.destCountry,
            "recipient_id" to transfer.recipientId.toString(),
            "recipient_name" to "${recipient.firstName} ${recipient.lastName}",
            "recipient_country" to recipient.country,
            "created_at" to transfer.createdAt.toString()
        )
        return objectMapper.writeValueAsString(payload)
    }

    // --- Cursor encoding/decoding ---

    private fun encodeCursor(createdAt: Instant, id: UUID): String {
        val json = objectMapper.writeValueAsString(mapOf("c" to createdAt.toString(), "i" to id.toString()))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    private fun decodeCursor(cursor: String): Pair<Instant, UUID> {
        return try {
            val json = String(Base64.getUrlDecoder().decode(cursor))
            val node = objectMapper.readTree(json)
            val createdAt = Instant.parse(node.get("c").asText())
            val id = UUID.fromString(node.get("i").asText())
            Pair(createdAt, id)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid cursor format: $cursor", e)
        }
    }
}
