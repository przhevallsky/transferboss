package com.swiftpay.llm.repository

import com.swiftpay.llm.model.KnowledgeDocument
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface KnowledgeDocumentRepository : JpaRepository<KnowledgeDocument, UUID> {

    fun findByCategory(category: String): List<KnowledgeDocument>
}
