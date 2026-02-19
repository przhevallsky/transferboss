package com.swiftpay.transfer.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.transfer.domain.model.*
import com.swiftpay.transfer.domain.vo.DeliveryMethod
import com.swiftpay.transfer.domain.vo.OutboxEventStatus
import com.swiftpay.transfer.domain.vo.OutboxEventType
import com.swiftpay.transfer.exception.*
import com.swiftpay.transfer.repository.*
import com.swiftpay.transfer.service.dto.CreateTransferCommand
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
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val objectMapper: ObjectMapper
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
     * @return Pair<Transfer, Boolean> — (перевод, isNew). isNew=false если idempotency hit.
     */
    @Transactional
    fun createTransfer(command: CreateTransferCommand): Pair<Transfer, Boolean> {

        // 1. IDEMPOTENCY CHECK: если ключ уже обработан — вернуть существующий перевод
        val existingTransfer = transferRepository.findByIdempotencyKey(command.idempotencyKey)
        if (existingTransfer != null) {
            log.info("Idempotency hit: key=${command.idempotencyKey}, transferId=${existingTransfer.id}")
            return Pair(existingTransfer, false)
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

        // 5. CREATE TRANSFER ENTITY
        // В MVP: receive_amount, exchange_rate, fee — заглушки.
        // В Sprint 2: gRPC вызов к Pricing Service для валидации quote и получения актуальных данных.
        val transfer = Transfer(
            idempotencyKey = command.idempotencyKey,
            senderId = command.senderId,
            quoteId = command.quoteId,
            sendAmount = command.sendAmount,
            sendCurrency = command.sendCurrency,
            receiveAmount = command.sendAmount, // TODO Sprint 2: из Pricing quote
            receiveCurrency = command.receiveCurrency,
            exchangeRate = BigDecimal.ONE,      // TODO Sprint 2: из Pricing quote
            feeAmount = BigDecimal.ZERO,        // TODO Sprint 2: из Pricing quote
            feeCurrency = command.sendCurrency,
            sourceCountry = command.sourceCountry,
            destCountry = command.destCountry,
            deliveryMethod = deliveryMethod,
            recipientId = command.recipientId,
            status = TransferStatus.Created
        )

        // 6. CREATE OUTBOX EVENT (в той же транзакции!)
        val outboxPayload = buildTransferCreatedPayload(transfer, recipient)
        val outboxEvent = OutboxEvent(
            entityId = transfer.id,
            entityType = "TRANSFER",
            eventType = OutboxEventType.TRANSFER_CREATED,
            payload = outboxPayload,
            status = OutboxEventStatus.PENDING
        )

        // 7. SAVE BOTH в одной транзакции (@Transactional на методе)
        val savedTransfer = transferRepository.save(transfer)
        outboxEventRepository.save(outboxEvent)

        log.info(
            "Transfer created: id={}, sender={}, corridor={}→{}, amount={} {}, idempotencyKey={}",
            savedTransfer.id, savedTransfer.senderId,
            savedTransfer.sourceCountry, savedTransfer.destCountry,
            savedTransfer.sendAmount, savedTransfer.sendCurrency,
            savedTransfer.idempotencyKey
        )

        return Pair(savedTransfer, true)
    }

    /**
     * Получить перевод по ID.
     * Redis cache (Cache-Aside) будет добавлен в Block 7.
     */
    @Transactional(readOnly = true)
    fun getTransfer(transferId: UUID): Transfer {
        return transferRepository.findTransferById(transferId)
            ?: throw TransferNotFoundException(transferId)
    }

    /**
     * Cursor-based pagination списка переводов.
     *
     * @param senderId фильтр по отправителю
     * @param cursor opaque cursor (Base64 encoded JSON), null для первой страницы
     * @param size размер страницы (default 20, max 100)
     * @return Pair<List<Transfer>, String?> — (результаты, nextCursor или null если больше нет)
     */
    @Transactional(readOnly = true)
    fun listTransfers(
        senderId: UUID,
        cursor: String?,
        size: Int
    ): Pair<List<Transfer>, String?> {

        val effectiveSize = size.coerceIn(1, 100)

        val transfers = if (cursor == null) {
            // Первая страница — используем Pageable
            transferRepository.findBySenderIdFirstPage(
                senderId = senderId,
                limit = PageRequest.of(0, effectiveSize + 1)
            )
        } else {
            // Декодируем cursor — используем native query с LIMIT
            val (cursorCreatedAt, cursorId) = decodeCursor(cursor)
            transferRepository.findBySenderIdAfterCursor(
                senderId = senderId,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
                limit = effectiveSize + 1
            )
        }

        // +1 trick: запросили size+1, если вернулось больше size — есть следующая страница
        val hasMore = transfers.size > effectiveSize
        val page = if (hasMore) transfers.take(effectiveSize) else transfers

        val nextCursor = if (hasMore && page.isNotEmpty()) {
            val lastItem = page.last()
            encodeCursor(lastItem.createdAt, lastItem.id)
        } else {
            null
        }

        return Pair(page, nextCursor)
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
