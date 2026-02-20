package com.swiftpay.transfer.domain

import com.swiftpay.transfer.domain.model.TransferStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class TransferStatusTest {

    // ============ Allowed transitions ============

    @Test
    fun `CREATED can transition to COMPLIANCE_CHECK`() {
        assertTrue(TransferStatus.Created.canTransitionTo(TransferStatus.ComplianceCheck))
    }

    @Test
    fun `CREATED can transition to CANCELLED`() {
        assertTrue(TransferStatus.Created.canTransitionTo(TransferStatus.Cancelled))
    }

    @Test
    fun `COMPLIANCE_CHECK can transition to PAYMENT_PENDING`() {
        assertTrue(TransferStatus.ComplianceCheck.canTransitionTo(TransferStatus.PaymentPending))
    }

    @Test
    fun `DELIVERING can transition to COMPLETED`() {
        assertTrue(TransferStatus.Delivering.canTransitionTo(TransferStatus.Completed))
    }

    // ============ Forbidden transitions ============

    @Test
    fun `COMPLETED cannot transition to any status`() {
        TransferStatus.ALL.forEach { status ->
            assertFalse(
                TransferStatus.Completed.canTransitionTo(status),
                "COMPLETED should not transition to ${status.value}"
            )
        }
    }

    @Test
    fun `CANCELLED cannot transition to any status`() {
        TransferStatus.ALL.forEach { status ->
            assertFalse(
                TransferStatus.Cancelled.canTransitionTo(status),
                "CANCELLED should not transition to ${status.value}"
            )
        }
    }

    @Test
    fun `CREATED cannot transition to COMPLETED directly`() {
        assertFalse(TransferStatus.Created.canTransitionTo(TransferStatus.Completed))
    }

    // ============ Terminal states ============

    @Test
    fun `COMPLETED is terminal`() {
        assertTrue(TransferStatus.Completed.isTerminal())
    }

    @Test
    fun `CANCELLED is terminal`() {
        assertTrue(TransferStatus.Cancelled.isTerminal())
    }

    @Test
    fun `REFUNDED is terminal`() {
        assertTrue(TransferStatus.Refunded.isTerminal())
    }

    @Test
    fun `CREATED is not terminal`() {
        assertFalse(TransferStatus.Created.isTerminal())
    }

    @Test
    fun `DELIVERING is not terminal`() {
        assertFalse(TransferStatus.Delivering.isTerminal())
    }

    // ============ Display status ============

    @Test
    fun `display status maps internal states to user-friendly names`() {
        assertEquals("PROCESSING", TransferStatus.ComplianceCheck.displayStatus())
        assertEquals("PROCESSING", TransferStatus.PaymentPending.displayStatus())
        assertEquals("PROCESSING", TransferStatus.PaymentCaptured.displayStatus())
        assertEquals("IN_TRANSIT", TransferStatus.Delivering.displayStatus())
        assertEquals("COMPLETED", TransferStatus.Completed.displayStatus())
        assertEquals("CREATED", TransferStatus.Created.displayStatus())
    }

    // ============ fromString ============

    @Test
    fun `should parse status from string value`() {
        assertEquals(TransferStatus.Created, TransferStatus.fromString("CREATED"))
        assertEquals(TransferStatus.Completed, TransferStatus.fromString("COMPLETED"))
        assertEquals(TransferStatus.ComplianceCheck, TransferStatus.fromString("COMPLIANCE_CHECK"))
        assertEquals(TransferStatus.Cancelled, TransferStatus.fromString("CANCELLED"))
    }

    @Test
    fun `should throw on unknown status string`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            TransferStatus.fromString("UNKNOWN")
        }
    }
}
