package com.swiftpay.transfer.service.dto

import java.math.BigDecimal
import java.util.UUID

/**
 * Команда на создание перевода. Поступает из контроллера после маппинга HTTP request → command.
 * Все поля уже провалидированы на уровне контроллера (формат, non-null).
 * Сервис выполняет бизнес-валидацию (коридор поддерживается, сумма в пределах лимитов и т.д.).
 */
data class CreateTransferCommand(
    val idempotencyKey: UUID,
    val senderId: UUID,
    val recipientId: UUID,
    val quoteId: UUID,
    val sendAmount: BigDecimal,
    val sendCurrency: String,
    val receiveCurrency: String,
    val sourceCountry: String,
    val destCountry: String,
    val deliveryMethod: String,
    val purpose: String? = null,
    val referenceNote: String? = null
)
