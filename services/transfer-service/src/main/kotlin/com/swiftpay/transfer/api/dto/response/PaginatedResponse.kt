package com.swiftpay.transfer.api.dto.response

/**
 * Обёртка для cursor-based pagination.
 * Используется для GET /api/v1/transfers.
 */
data class PaginatedResponse<T>(
    val items: List<T>,
    val pagination: PaginationMeta
)

data class PaginationMeta(
    val nextCursor: String?,
    val hasMore: Boolean
)
