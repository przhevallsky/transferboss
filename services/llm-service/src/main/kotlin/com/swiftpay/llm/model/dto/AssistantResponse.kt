package com.swiftpay.llm.model.dto

import java.util.UUID

data class AssistantResponse(
    val answer: String,
    val sources: List<SourceReference>,
    val conversationId: String
)

data class SourceReference(
    val documentId: UUID,
    val title: String,
    val relevanceScore: Double
)
