package com.swiftpay.llm.service

import com.swiftpay.llm.client.LlmClient
import com.swiftpay.llm.metrics.LlmMetrics
import com.swiftpay.llm.model.dto.AssistantResponse
import com.swiftpay.llm.model.dto.SourceReference
import com.swiftpay.llm.rag.EmbeddingService
import com.swiftpay.llm.rag.VectorSearchService
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class RagService(
    private val embeddingService: EmbeddingService,
    private val vectorSearchService: VectorSearchService,
    private val llmClient: LlmClient,
    private val llmMetrics: LlmMetrics
) {

    private val log = LoggerFactory.getLogger(RagService::class.java)

    companion object {
        private const val DEFAULT_TOP_K = 5

        private val SYSTEM_PROMPT = """
            You are a helpful customer support assistant for SwiftPay, a money transfer service.
            Answer the user's question based on the provided context. If the context doesn't contain
            enough information to answer the question, say so honestly.
            Always be polite, concise, and accurate.
        """.trimIndent()
    }

    @CircuitBreaker(name = "openai-api", fallbackMethod = "fallbackAsk")
    suspend fun ask(question: String, category: String? = null): AssistantResponse {
        llmMetrics.incrementRequestCount("ask", "success")

        val timer = llmMetrics.startTimer()
        try {
            // Step 1: Generate embedding for the query
            val queryEmbedding = embeddingService.generateEmbedding(question)
            llmMetrics.incrementVectorSearchCount()

            // Step 2: Vector search for relevant documents
            val searchResults = vectorSearchService.searchSimilarDocuments(queryEmbedding, DEFAULT_TOP_K)

            // Step 3: Build context from retrieved documents
            val context = buildContext(searchResults)

            // Step 4: Build the prompt with context
            val prompt = buildPrompt(question, context)

            // Step 5: Call LLM
            val answer = llmClient.generateCompletion(prompt, SYSTEM_PROMPT)

            // Step 6: Build source references
            val sources = searchResults.map { result ->
                SourceReference(
                    documentId = result.id,
                    title = result.title,
                    relevanceScore = result.similarity
                )
            }

            val conversationId = UUID.randomUUID().toString()

            return AssistantResponse(
                answer = answer,
                sources = sources,
                conversationId = conversationId
            )
        } finally {
            llmMetrics.recordDuration(timer)
        }
    }

    suspend fun askStream(question: String): Flow<String> {
        llmMetrics.incrementRequestCount("stream", "success")

        // Step 1: Generate embedding for the query
        val queryEmbedding = embeddingService.generateEmbedding(question)
        llmMetrics.incrementVectorSearchCount()

        // Step 2: Vector search for relevant documents
        val searchResults = vectorSearchService.searchSimilarDocuments(queryEmbedding, DEFAULT_TOP_K)

        // Step 3: Build context and prompt
        val context = buildContext(searchResults)
        val prompt = buildPrompt(question, context)

        // Step 4: Stream LLM response
        return llmClient.generateCompletionStream(prompt, SYSTEM_PROMPT)
    }

    @Suppress("unused")
    suspend fun fallbackAsk(question: String, category: String?, throwable: Throwable): AssistantResponse {
        log.warn("Circuit breaker triggered for ask, using fallback. Error: {}", throwable.message)
        llmMetrics.incrementRequestCount("ask", "error")

        // Try to provide a helpful response from retrieved documents without calling the LLM
        return try {
            val queryEmbedding = embeddingService.generateEmbedding(question)
            val searchResults = vectorSearchService.searchSimilarDocuments(queryEmbedding, DEFAULT_TOP_K)

            val fallbackAnswer = if (searchResults.isNotEmpty()) {
                val topResult = searchResults.first()
                "I'm currently experiencing issues with my AI capabilities, but based on our knowledge base, " +
                    "here's what I found:\n\n${topResult.content}\n\n" +
                    "For more detailed assistance, please contact our support team."
            } else {
                "I'm sorry, I'm currently unable to process your request. Please try again later " +
                    "or contact our support team for immediate assistance."
            }

            val sources = searchResults.map { result ->
                SourceReference(
                    documentId = result.id,
                    title = result.title,
                    relevanceScore = result.similarity
                )
            }

            AssistantResponse(
                answer = fallbackAnswer,
                sources = sources,
                conversationId = UUID.randomUUID().toString()
            )
        } catch (e: Exception) {
            log.error("Fallback also failed: {}", e.message)
            AssistantResponse(
                answer = "I'm sorry, I'm currently unable to process your request. " +
                    "Please try again later or contact our support team.",
                sources = emptyList(),
                conversationId = UUID.randomUUID().toString()
            )
        }
    }

    private fun buildContext(results: List<VectorSearchService.SearchResult>): String {
        if (results.isEmpty()) return "No relevant information found."

        return results.joinToString("\n\n") { result ->
            "--- ${result.title} (relevance: ${"%.2f".format(result.similarity)}) ---\n${result.content}"
        }
    }

    private fun buildPrompt(question: String, context: String): String {
        return """
            Context from knowledge base:
            $context

            User question: $question

            Please answer the question based on the context provided above.
        """.trimIndent()
    }
}
