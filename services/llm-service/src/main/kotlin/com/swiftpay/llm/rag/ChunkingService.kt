package com.swiftpay.llm.rag

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ChunkingService(
    private val chunkSize: Int = 500,
    private val chunkOverlap: Int = 50
) {

    private val log = LoggerFactory.getLogger(ChunkingService::class.java)

    fun chunkText(text: String): List<String> {
        if (text.length <= chunkSize) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            val chunk = text.substring(start, end).trim()
            if (chunk.isNotEmpty()) {
                chunks.add(chunk)
            }
            start += chunkSize - chunkOverlap
        }

        log.debug("Split text of length {} into {} chunks (size={}, overlap={})",
            text.length, chunks.size, chunkSize, chunkOverlap)
        return chunks
    }
}
