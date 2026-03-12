package com.swiftpay.llm.rag

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class VectorSearchService(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    private val log = LoggerFactory.getLogger(VectorSearchService::class.java)

    data class SearchResult(
        val id: UUID,
        val title: String,
        val content: String,
        val source: String?,
        val category: String?,
        val similarity: Double
    )

    fun searchSimilarDocuments(queryVector: List<Float>, limit: Int = 5): List<SearchResult> {
        val vectorStr = "[${queryVector.joinToString(",")}]"

        val sql = """
            SELECT id, title, content, source, category,
                   1 - (embedding <=> :queryVector::vector) AS similarity
            FROM knowledge_documents
            WHERE embedding IS NOT NULL
            ORDER BY embedding <=> :queryVector::vector
            LIMIT :limit
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("queryVector", vectorStr)
            .addValue("limit", limit)

        log.debug("Executing vector search with limit={}", limit)

        return jdbcTemplate.query(sql, params) { rs, _ ->
            SearchResult(
                id = UUID.fromString(rs.getString("id")),
                title = rs.getString("title"),
                content = rs.getString("content"),
                source = rs.getString("source"),
                category = rs.getString("category"),
                similarity = rs.getDouble("similarity")
            )
        }
    }
}
