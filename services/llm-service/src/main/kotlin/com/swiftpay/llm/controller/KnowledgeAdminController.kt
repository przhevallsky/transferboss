package com.swiftpay.llm.controller

import com.swiftpay.llm.model.KnowledgeDocument
import com.swiftpay.llm.rag.DocumentLoader
import com.swiftpay.llm.repository.KnowledgeDocumentRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/knowledge")
class KnowledgeAdminController(
    private val repository: KnowledgeDocumentRepository,
    private val documentLoader: DocumentLoader
) {

    @PostMapping("/ingest")
    fun ingestDocuments(): ResponseEntity<Map<String, String>> {
        documentLoader.loadDocuments()
        return ResponseEntity.ok(mapOf("status" to "Document ingestion triggered"))
    }

    @GetMapping("/documents")
    fun listDocuments(@PageableDefault(size = 20) pageable: Pageable): Page<KnowledgeDocument> {
        return repository.findAll(pageable)
    }

    @DeleteMapping("/documents/{id}")
    fun deleteDocument(@PathVariable id: UUID): ResponseEntity<Void> {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build()
        }
        repository.deleteById(id)
        return ResponseEntity.noContent().build()
    }
}
