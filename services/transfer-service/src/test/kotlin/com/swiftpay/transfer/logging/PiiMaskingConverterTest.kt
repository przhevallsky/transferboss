package com.swiftpay.transfer.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PiiMaskingConverterTest {

    @Test
    fun `should mask email addresses`() {
        val input = "User email is john.doe@example.com and notified"
        val result = PiiMaskingConverter.mask(input)
        assertEquals("User email is j***@example.com and notified", result)
    }

    @Test
    fun `should mask single char email prefix`() {
        val input = "Email: a@test.io"
        val result = PiiMaskingConverter.mask(input)
        assertEquals("Email: ***@test.io", result)
    }

    @Test
    fun `should mask phone numbers`() {
        val input = "Contact phone: +1-555-123-4567 for support"
        val result = PiiMaskingConverter.mask(input)
        assertEquals("Contact phone: ***4567 for support", result)
    }

    @Test
    fun `should mask SSN`() {
        val input = "SSN: 123-45-6789 on file"
        val result = PiiMaskingConverter.mask(input)
        assertEquals("SSN: ***-**-**** on file", result)
    }

    @Test
    fun `should mask credit card numbers`() {
        val input = "Card: 4111-1111-1111-1234 charged"
        val result = PiiMaskingConverter.mask(input)
        assertEquals("Card: ****-****-****-1234 charged", result)
    }

    @Test
    fun `should mask credit card without dashes`() {
        val input = "Card: 4111111111111234 charged"
        val result = PiiMaskingConverter.mask(input)
        assertEquals("Card: ****-****-****-1234 charged", result)
    }

    @Test
    fun `should mask multiple PII in one message`() {
        val input = "User john@example.com with SSN 123-45-6789 called"
        val result = PiiMaskingConverter.mask(input)
        assertEquals("User j***@example.com with SSN ***-**-**** called", result)
    }

    @Test
    fun `should not modify message without PII`() {
        val input = "Transfer abc123 created for amount 200.00 USD"
        val result = PiiMaskingConverter.mask(input)
        assertEquals(input, result)
    }

    @Test
    fun `should not mask UUIDs`() {
        val input = "Transfer 550e8400-e29b-41d4-a716-446655440000 created"
        val result = PiiMaskingConverter.mask(input)
        assertEquals(input, result)
    }
}
