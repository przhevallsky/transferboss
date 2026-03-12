package com.swiftpay.llm.rag

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import kotlin.math.abs
import kotlin.math.sqrt

@Service
@Profile("dev")
class MockEmbeddingService : EmbeddingService {

    private val log = LoggerFactory.getLogger(MockEmbeddingService::class.java)

    companion object {
        private const val EMBEDDING_DIMENSION = 1536
    }

    override suspend fun generateEmbedding(text: String): List<Float> {
        log.debug("Generating mock embedding for text: {}", text.take(50))
        return generateDeterministicVector(text)
    }

    override suspend fun generateEmbeddings(texts: List<String>): List<List<Float>> {
        log.debug("Generating mock embeddings for {} texts", texts.size)
        return texts.map { generateDeterministicVector(it) }
    }

    private fun generateDeterministicVector(text: String): List<Float> {
        val hash = text.hashCode().toLong()
        val vector = FloatArray(EMBEDDING_DIMENSION) { i ->
            val seed = hash + i.toLong()
            val raw = abs((seed * 2654435761L) % 10000).toFloat() / 10000f
            raw * 2f - 1f // Normalize to [-1, 1]
        }

        // Normalize the vector to unit length
        val magnitude = sqrt(vector.map { it * it }.sum())
        if (magnitude > 0) {
            for (i in vector.indices) {
                vector[i] = vector[i] / magnitude
            }
        }

        return vector.toList()
    }
}
