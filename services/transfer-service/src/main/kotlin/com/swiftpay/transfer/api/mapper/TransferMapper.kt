package com.swiftpay.transfer.api.mapper

import com.swiftpay.transfer.api.dto.request.CreateTransferRequest
import com.swiftpay.transfer.api.dto.response.RecipientBrief
import com.swiftpay.transfer.api.dto.response.TransferResponse
import com.swiftpay.transfer.domain.model.Recipient
import com.swiftpay.transfer.domain.model.Transfer
import com.swiftpay.transfer.service.dto.CreateTransferCommand
import java.util.UUID

/**
 * Маппинг между слоями: HTTP ↔ Service ↔ Domain.
 *
 * Реализован как object (singleton) с extension functions — идиоматический Kotlin подход.
 */
object TransferMapper {

    /**
     * HTTP Request → Service Command.
     * senderId приходит из JWT (контроллер извлекает), не из request body.
     */
    fun CreateTransferRequest.toCommand(
        senderId: UUID,
        idempotencyKey: UUID
    ): CreateTransferCommand = CreateTransferCommand(
        idempotencyKey = idempotencyKey,
        senderId = senderId,
        recipientId = recipientId!!,
        quoteId = quoteId!!,
        sendAmount = sendAmount!!,
        sendCurrency = sendCurrency!!,
        receiveCurrency = receiveCurrency!!,
        sourceCountry = sourceCountry!!,
        destCountry = destCountry!!,
        deliveryMethod = deliveryMethod!!,
        purpose = purpose,
        referenceNote = referenceNote
    )

    /**
     * Domain Entity → HTTP Response.
     */
    fun Transfer.toResponse(recipient: Recipient? = null): TransferResponse = TransferResponse(
        id = id.toString(),
        status = status.value,
        displayStatus = status.displayStatus(),
        sendAmount = sendAmount.toPlainString(),
        sendCurrency = sendCurrency,
        receiveAmount = receiveAmount.toPlainString(),
        receiveCurrency = receiveCurrency,
        exchangeRate = exchangeRate.toPlainString(),
        feeAmount = feeAmount.toPlainString(),
        deliveryMethod = deliveryMethod.name,
        sourceCountry = sourceCountry,
        destCountry = destCountry,
        recipient = recipient?.toBrief() ?: RecipientBrief(
            id = recipientId.toString(),
            firstName = "—",
            lastName = "—"
        ),
        createdAt = createdAt,
        statusReason = statusReason
    )

    fun Recipient.toBrief(): RecipientBrief = RecipientBrief(
        id = id.toString(),
        firstName = firstName,
        lastName = lastName
    )
}
