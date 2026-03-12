package com.swiftpay.llm.rag

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.swiftpay.llm.model.KnowledgeDocument
import com.swiftpay.llm.repository.KnowledgeDocumentRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

@Service
class DocumentLoader(
    private val repository: KnowledgeDocumentRepository,
    private val chunkingService: ChunkingService,
    private val embeddingService: EmbeddingService,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(DocumentLoader::class.java)
    private val loaded = AtomicBoolean(false)

    data class FaqEntry(
        val title: String,
        val content: String,
        val category: String
    )

    @EventListener(ApplicationReadyEvent::class)
    fun loadDocuments() {
        if (!loaded.compareAndSet(false, true)) {
            log.info("Documents already loaded, skipping")
            return
        }

        if (repository.count() > 0) {
            log.info("Knowledge documents already exist in database, skipping load")
            return
        }

        try {
            val resource = ClassPathResource("knowledge/faq.json")
            val faqEntries: List<FaqEntry> = objectMapper.readValue(resource.inputStream)

            log.info("Loading {} FAQ entries into knowledge base", faqEntries.size)

            val documents = mutableListOf<KnowledgeDocument>()

            for (entry in faqEntries) {
                val chunks = chunkingService.chunkText(entry.content)
                chunks.forEachIndexed { index, chunk ->
                    val embedding = runBlocking {
                        embeddingService.generateEmbedding(chunk)
                    }
                    val embeddingStr = "[${embedding.joinToString(",")}]"

                    documents.add(
                        KnowledgeDocument(
                            title = entry.title,
                            content = chunk,
                            chunkIndex = index,
                            source = "faq.json",
                            category = entry.category,
                            embedding = embeddingStr,
                            metadata = objectMapper.writeValueAsString(
                                mapOf("originalTitle" to entry.title, "totalChunks" to chunks.size)
                            )
                        )
                    )
                }
            }

            repository.saveAll(documents)
            log.info("Successfully loaded {} document chunks into knowledge base", documents.size)
        } catch (e: Exception) {
            log.error("Failed to load FAQ documents: {}", e.message, e)
            loaded.set(false)
        }
    }
}
