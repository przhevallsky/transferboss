package com.swiftpay.llm.model

import com.fasterxml.jackson.annotation.JsonProperty

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @JsonProperty("max_tokens")
    val maxTokens: Int,
    val temperature: Double,
    val stream: Boolean = false
)
