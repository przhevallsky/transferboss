package com.swiftpay.llm.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "knowledge_documents")
class KnowledgeDocument(

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    val id: UUID? = null,

    @Column(name = "title", nullable = false, length = 500)
    var title: String,

    @Column(name = "content", nullable = false, columnDefinition = "text")
    var content: String,

    @Column(name = "chunk_index", nullable = false)
    var chunkIndex: Int = 0,

    @Column(name = "source", length = 255)
    var source: String? = null,

    @Column(name = "category", length = 100)
    var category: String? = null,

    @Column(name = "embedding", columnDefinition = "vector(1536)")
    var embedding: String? = null,

    @Column(name = "metadata", columnDefinition = "jsonb")
    var metadata: String = "{}",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
)
