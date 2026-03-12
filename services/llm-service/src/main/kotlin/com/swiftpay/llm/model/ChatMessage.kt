package com.swiftpay.llm.model

import com.fasterxml.jackson.annotation.JsonProperty

data class ChatMessage(
    val role: String,
    val content: String
)
