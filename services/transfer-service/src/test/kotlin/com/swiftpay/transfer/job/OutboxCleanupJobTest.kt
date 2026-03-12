package com.swiftpay.transfer.job

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class OutboxCleanupJobTest {

    private val jdbcTemplate: JdbcTemplate = mockk()
    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var job: OutboxCleanupJob

    @BeforeEach
    fun setup() {
        job = OutboxCleanupJob(jdbcTemplate, meterRegistry)
    }

    @Test
    fun `should delete processed events in batches`() {
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returnsMany listOf(1000, 500)

        job.cleanupProcessedEvents()

        verify(exactly = 2) { jdbcTemplate.update(any<String>(), *anyVararg()) }

        val counter = meterRegistry.find("outbox_cleanup_deleted_total").counter()
        assertEquals(1500.0, counter?.count())
    }

    @Test
    fun `should handle no records to delete`() {
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 0

        job.cleanupProcessedEvents()

        verify(exactly = 1) { jdbcTemplate.update(any<String>(), *anyVararg()) }

        val counter = meterRegistry.find("outbox_cleanup_deleted_total").counter()
        assertEquals(null, counter)
    }

    @Test
    fun `should delete single batch when less than batch size`() {
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 250

        job.cleanupProcessedEvents()

        verify(exactly = 1) { jdbcTemplate.update(any<String>(), *anyVararg()) }

        val counter = meterRegistry.find("outbox_cleanup_deleted_total").counter()
        assertEquals(250.0, counter?.count())
    }

    @Test
    fun `should loop exactly until batch returns less than batch size`() {
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returnsMany listOf(1000, 1000, 1000, 300)

        job.cleanupProcessedEvents()

        verify(exactly = 4) { jdbcTemplate.update(any<String>(), *anyVararg()) }

        val counter = meterRegistry.find("outbox_cleanup_deleted_total").counter()
        assertEquals(3300.0, counter?.count())
    }
}
