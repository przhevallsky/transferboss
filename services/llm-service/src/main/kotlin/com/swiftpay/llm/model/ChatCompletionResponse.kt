package com.swiftpay.llm.model

import com.fasterxml.jackson.annotation.JsonProperty

data class ChatCompletionResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage? = null
) {
    data class Choice(
        val index: Int,
        val message: ChatMessage,
        @JsonProperty("finish_reason")
        val finishReason: String? = null
    )

    data class Usage(
        @JsonProperty("prompt_tokens")
        val promptTokens: Int,
        @JsonProperty("completion_tokens")
        val completionTokens: Int,
        @JsonProperty("total_tokens")
        val totalTokens: Int
    )
}
