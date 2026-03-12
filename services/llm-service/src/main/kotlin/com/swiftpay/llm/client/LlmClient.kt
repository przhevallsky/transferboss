package com.swiftpay.llm.client

import kotlinx.coroutines.flow.Flow

interface LlmClient {
    suspend fun generateCompletion(prompt: String, systemPrompt: String? = null): String
    suspend fun generateCompletionStream(prompt: String, systemPrompt: String? = null): Flow<String>
}
