package com.swiftpay.llm.rag

interface EmbeddingService {
    suspend fun generateEmbedding(text: String): List<Float>
    suspend fun generateEmbeddings(texts: List<String>): List<List<Float>>
}
