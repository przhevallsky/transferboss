package com.swiftpay.llm.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.llm.config.OpenAiProperties
import com.swiftpay.llm.model.ChatCompletionRequest
import com.swiftpay.llm.model.ChatCompletionResponse
import com.swiftpay.llm.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Component
@Profile("!dev")
class OpenAiClient(
    private val openAiWebClient: WebClient,
    private val properties: OpenAiProperties,
    private val objectMapper: ObjectMapper
) : LlmClient {

    private val log = LoggerFactory.getLogger(OpenAiClient::class.java)

    override suspend fun generateCompletion(prompt: String, systemPrompt: String?): String {
        val messages = buildMessages(prompt, systemPrompt)
        val request = ChatCompletionRequest(
            model = properties.model,
            messages = messages,
            maxTokens = properties.maxTokens,
            temperature = properties.temperature,
            stream = false
        )

        log.debug("Sending completion request to OpenAI: model={}", properties.model)

        val response = openAiWebClient.post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .awaitBody<ChatCompletionResponse>()

        return response.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("No completion returned from OpenAI")
    }

    override suspend fun generateCompletionStream(prompt: String, systemPrompt: String?): Flow<String> {
        val messages = buildMessages(prompt, systemPrompt)
        val request = ChatCompletionRequest(
            model = properties.model,
            messages = messages,
            maxTokens = properties.maxTokens,
            temperature = properties.temperature,
            stream = true
        )

        log.debug("Sending streaming completion request to OpenAI: model={}", properties.model)

        val eventStream = openAiWebClient.post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(ServerSentEvent::class.java)

        return flow {
            eventStream.asFlow().collect { event ->
                val data = event.data()?.toString() ?: return@collect
                if (data == "[DONE]") return@collect

                try {
                    val node = objectMapper.readTree(data)
                    val content = node.path("choices")
                        .firstOrNull()
                        ?.path("delta")
                        ?.path("content")
                        ?.asText()
                    if (!content.isNullOrEmpty()) {
                        emit(content)
                    }
                } catch (e: Exception) {
                    log.warn("Failed to parse SSE chunk: {}", e.message)
                }
            }
        }
    }

    private fun buildMessages(prompt: String, systemPrompt: String?): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        if (systemPrompt != null) {
            messages.add(ChatMessage(role = "system", content = systemPrompt))
        }
        messages.add(ChatMessage(role = "user", content = prompt))
        return messages
    }
}
