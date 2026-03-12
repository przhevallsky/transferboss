package com.swiftpay.transfer.job

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class OutboxCleanupJob(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(OutboxCleanupJob::class.java)

    companion object {
        private const val BATCH_SIZE = 1000
        private const val RETENTION_DAYS = 7L
    }

    @Scheduled(cron = "0 0 3 * * *")
    fun cleanupProcessedEvents() {
        val cutoff = Timestamp.from(Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS))
        var totalDeleted = 0

        do {
            val deleted = jdbcTemplate.update(
                """
                DELETE FROM outbox_events WHERE id IN (
                    SELECT id FROM outbox_events
                    WHERE status = 'SENT' AND processed_at < ?
                    LIMIT ?
                )
                """.trimIndent(),
                cutoff,
                BATCH_SIZE
            )
            totalDeleted += deleted
        } while (deleted == BATCH_SIZE)

        if (totalDeleted > 0) {
            meterRegistry.counter("outbox_cleanup_deleted_total").increment(totalDeleted.toDouble())
        }
        log.info("Outbox cleanup: deleted {} processed events older than {} days", totalDeleted, RETENTION_DAYS)
    }
}
