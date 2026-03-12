package com.swiftpay.migration

import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

@Component
class MigrationRunner(
    private val dataSource: DataSource,
    @Value("\${mongodb.uri}") private val mongoUri: String,
    @Value("\${mongodb.database}") private val mongoDatabase: String,
    @Value("\${migration.batch-size:500}") private val batchSize: Int,
    @Value("\${migration.dry-run:false}") private val dryRun: Boolean,
    @Value("\${migration.collection:corridors}") private val collectionName: String,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(MigrationRunner::class.java)

    override fun run(vararg args: String?) {
        log.info("Starting MongoDB -> PostgreSQL migration (dry-run=$dryRun, batch-size=$batchSize)")

        dataSource.connection.use { conn ->
            if (!acquireAdvisoryLock(conn)) {
                log.warn("Another migration instance is running. Exiting.")
                return
            }

            try {
                runBlocking<Unit> {
                    executeMigration(conn)
                }
            } finally {
                releaseAdvisoryLock(conn)
            }
        }
    }

    private suspend fun executeMigration(conn: Connection) {
        val mongoClient = MongoClient.create(mongoUri)
        mongoClient.use { client ->
            val database = client.getDatabase(mongoDatabase)
            val collection: MongoCollection<Document> = database.getCollection(collectionName)

            var totalMigrated = 0
            var totalErrors = 0
            var lastProcessedId: ObjectId? = loadLastProcessedId(conn)

            log.info("Resuming migration from last processed ID: $lastProcessedId")

            while (true) {
                val filter = if (lastProcessedId != null) {
                    Document("_id", Document("\$gt", lastProcessedId))
                } else {
                    Document()
                }

                val batch = collection.find(filter)
                    .sort(Document("_id", 1))
                    .limit(batchSize)
                    .toList()

                if (batch.isEmpty()) {
                    log.info("No more documents to process.")
                    break
                }

                log.info("Processing batch of ${batch.size} documents")

                for (doc in batch) {
                    try {
                        if (dryRun) {
                            logDryRun(doc)
                        } else {
                            migrateDocument(conn, doc)
                        }
                        totalMigrated++
                        lastProcessedId = doc.getObjectId("_id")
                    } catch (e: Exception) {
                        totalErrors++
                        log.error("Failed to migrate document ${doc.getObjectId("_id")}: ${e.message}", e)

                        // Retry once
                        try {
                            if (!dryRun) {
                                migrateDocument(conn, doc)
                                totalMigrated++
                                totalErrors-- // Undo the error count on successful retry
                                lastProcessedId = doc.getObjectId("_id")
                                log.info("Retry succeeded for document ${doc.getObjectId("_id")}")
                            }
                        } catch (retryEx: Exception) {
                            log.error(
                                "Retry also failed for document ${doc.getObjectId("_id")}: ${retryEx.message}",
                                retryEx
                            )
                        }
                    }
                }

                // Persist progress after each batch
                if (!dryRun && lastProcessedId != null) {
                    saveLastProcessedId(conn, lastProcessedId)
                }
            }

            log.info("=== Migration Summary ===")
            log.info("Total migrated: $totalMigrated")
            log.info("Total errors:   $totalErrors")
            log.info("Dry-run:        $dryRun")
            log.info("=========================")
        }
    }

    private fun migrateDocument(conn: Connection, doc: Document) {
        val sql = """
            INSERT INTO pricing_corridors (
                source_country, destination_country, source_currency,
                destination_currency, base_fee, fee_percentage,
                exchange_rate_margin, min_amount, max_amount,
                is_active, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_country, destination_country, source_currency, destination_currency)
            DO UPDATE SET
                base_fee = EXCLUDED.base_fee,
                fee_percentage = EXCLUDED.fee_percentage,
                exchange_rate_margin = EXCLUDED.exchange_rate_margin,
                min_amount = EXCLUDED.min_amount,
                max_amount = EXCLUDED.max_amount,
                is_active = EXCLUDED.is_active,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, doc.getString("source_country"))
            stmt.setString(2, doc.getString("destination_country"))
            stmt.setString(3, doc.getString("source_currency"))
            stmt.setString(4, doc.getString("destination_currency"))
            stmt.setBigDecimal(5, toBigDecimal(doc, "base_fee"))
            stmt.setBigDecimal(6, toBigDecimal(doc, "fee_percentage"))
            stmt.setBigDecimal(7, toBigDecimal(doc, "exchange_rate_margin"))
            stmt.setBigDecimal(8, toBigDecimal(doc, "min_amount"))
            stmt.setBigDecimal(9, toBigDecimal(doc, "max_amount"))
            stmt.setBoolean(10, doc.getBoolean("is_active", true))
            stmt.setObject(11, Instant.now())
            stmt.setObject(12, Instant.now())
            stmt.executeUpdate()
        }
    }

    private fun logDryRun(doc: Document) {
        log.info(
            "[DRY-RUN] Would migrate corridor: {} -> {} ({}/{})",
            doc.getString("source_country"),
            doc.getString("destination_country"),
            doc.getString("source_currency"),
            doc.getString("destination_currency")
        )
    }

    private fun toBigDecimal(doc: Document, field: String): BigDecimal {
        return when (val value = doc.get(field)) {
            is Double -> BigDecimal.valueOf(value)
            is Int -> BigDecimal.valueOf(value.toLong())
            is Long -> BigDecimal.valueOf(value)
            is String -> BigDecimal(value)
            is org.bson.types.Decimal128 -> value.bigDecimalValue()
            null -> BigDecimal.ZERO
            else -> BigDecimal(value.toString())
        }
    }

    // ── Advisory Lock ──────────────────────────────────────────────────────────

    private fun acquireAdvisoryLock(conn: Connection): Boolean {
        val lockId = 123456789L // Unique lock ID for this migration
        conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { stmt ->
            stmt.setLong(1, lockId)
            stmt.executeQuery().use { rs ->
                rs.next()
                return rs.getBoolean(1)
            }
        }
    }

    private fun releaseAdvisoryLock(conn: Connection) {
        val lockId = 123456789L
        conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { stmt ->
            stmt.setLong(1, lockId)
            stmt.execute()
        }
    }

    // ── Migration Progress Tracking ────────────────────────────────────────────

    private fun loadLastProcessedId(conn: Connection): ObjectId? {
        ensureProgressTableExists(conn)
        conn.prepareStatement(
            "SELECT last_processed_id FROM migration_progress WHERE migration_name = ?"
        ).use { stmt ->
            stmt.setString(1, collectionName)
            stmt.executeQuery().use { rs ->
                return if (rs.next()) {
                    val idStr = rs.getString("last_processed_id")
                    if (idStr != null) ObjectId(idStr) else null
                } else {
                    null
                }
            }
        }
    }

    private fun saveLastProcessedId(conn: Connection, id: ObjectId) {
        conn.prepareStatement(
            """
            INSERT INTO migration_progress (migration_name, last_processed_id, updated_at)
            VALUES (?, ?, NOW())
            ON CONFLICT (migration_name) DO UPDATE SET
                last_processed_id = EXCLUDED.last_processed_id,
                updated_at = NOW()
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, collectionName)
            stmt.setString(2, id.toHexString())
            stmt.executeUpdate()
        }
    }

    private fun ensureProgressTableExists(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS migration_progress (
                    migration_name VARCHAR(255) PRIMARY KEY,
                    last_processed_id VARCHAR(255),
                    updated_at TIMESTAMP DEFAULT NOW()
                )
                """.trimIndent()
            )
        }
    }
}
