package com.swiftpay.transfer.exception

/**
 * Базовый класс для бизнес-ошибок.
 * Все бизнес-ошибки наследуются от него — это позволяет в @RestControllerAdvice (Block 6)
 * маппить их единообразно в RFC 9457 Problem Details.
 *
 * errorType — URI типа ошибки для Problem Details "type" field.
 */
abstract class BusinessException(
    val errorType: String,
    val title: String,
    val statusCode: Int,
    override val message: String
) : RuntimeException(message)
