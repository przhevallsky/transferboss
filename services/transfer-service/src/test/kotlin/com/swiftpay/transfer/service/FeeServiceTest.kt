package com.swiftpay.transfer.service

import com.swiftpay.transfer.client.QuoteData
import io.getunleash.FakeUnleash
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FeeServiceTest {

    private val fakeUnleash = FakeUnleash()
    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var feeService: FeeService

    private val senderId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        feeService = FeeService(fakeUnleash, meterRegistry)
    }

    private fun quoteData(
        sendAmount: BigDecimal = BigDecimal("200.00"),
        feeAmount: BigDecimal = BigDecimal("5.99"),
        exchangeRate: BigDecimal = BigDecimal("56.20"),
        receiveAmount: BigDecimal = BigDecimal("10907.72")
    ) = QuoteData(
        quoteId = UUID.randomUUID().toString(),
        sendAmount = sendAmount,
        receiveAmount = receiveAmount,
        exchangeRate = exchangeRate,
        feeAmount = feeAmount,
        feeCurrency = "USD",
        sendCurrency = "USD",
        receiveCurrency = "PHP"
    )

    @Nested
    inner class LegacyAlgorithm {

        @Test
        fun `should return quoteData unchanged when flag disabled`() {
            fakeUnleash.disable("new-pricing-algorithm")
            val original = quoteData()

            val result = feeService.applyFeeStrategy(senderId, original)

            assertEquals(original.feeAmount, result.feeAmount)
            assertEquals(original.receiveAmount, result.receiveAmount)
            assertEquals(original.sendAmount, result.sendAmount)
        }

        @Test
        fun `should increment legacy metric counter`() {
            fakeUnleash.disable("new-pricing-algorithm")

            feeService.applyFeeStrategy(senderId, quoteData())

            val count = meterRegistry.counter("pricing.fee.calculation.total", "algorithm", "legacy").count()
            assertEquals(1.0, count)
        }
    }

    @Nested
    inner class TieredAlgorithm {

        @BeforeEach
        fun enableFlag() {
            fakeUnleash.enable("new-pricing-algorithm")
        }

        @Test
        fun `should recalculate fee with tiered pricing when flag enabled`() {
            val original = quoteData(sendAmount = BigDecimal("200.00"))

            val result = feeService.applyFeeStrategy(senderId, original)

            // Tier 1: 100 * 0.01 = 1.00
            // Tier 2: (200 - 100) * 0.008 = 0.80
            // Total: 1.80
            assertEquals(BigDecimal("1.80"), result.feeAmount)
            // receiveAmount = (200.00 - 1.80) * 56.20 = 198.20 * 56.20 = 11138.84
            assertEquals(BigDecimal("11138.84"), result.receiveAmount)
        }

        @Test
        fun `should use tiered for small amounts - more favorable than flat fee`() {
            val original = quoteData(
                sendAmount = BigDecimal("50.00"),
                feeAmount = BigDecimal("5.99"),
                exchangeRate = BigDecimal("56.20")
            )

            val result = feeService.applyFeeStrategy(senderId, original)

            // 50 * 0.01 = 0.50 → but min is 0.99
            assertEquals(BigDecimal("0.99"), result.feeAmount)
            // Original flat fee was 5.99, tiered is 0.99 — much more favorable
        }

        @Test
        fun `should calculate tier 3 for large amounts`() {
            val original = quoteData(
                sendAmount = BigDecimal("1000.00"),
                feeAmount = BigDecimal("5.99"),
                exchangeRate = BigDecimal("56.20")
            )

            val result = feeService.applyFeeStrategy(senderId, original)

            // Tier 1: 100 * 0.01 = 1.00
            // Tier 2: 400 * 0.008 = 3.20
            // Tier 3: (1000 - 500) * 0.005 = 2.50
            // Total: 6.70
            assertEquals(BigDecimal("6.70"), result.feeAmount)
        }

        @Test
        fun `should increment tiered metric counter`() {
            feeService.applyFeeStrategy(senderId, quoteData())

            val count = meterRegistry.counter("pricing.fee.calculation.total", "algorithm", "tiered").count()
            assertEquals(1.0, count)
        }

        @Test
        fun `should preserve non-fee fields in quoteData`() {
            val original = quoteData()

            val result = feeService.applyFeeStrategy(senderId, original)

            assertEquals(original.quoteId, result.quoteId)
            assertEquals(original.sendAmount, result.sendAmount)
            assertEquals(original.exchangeRate, result.exchangeRate)
            assertEquals(original.feeCurrency, result.feeCurrency)
            assertEquals(original.sendCurrency, result.sendCurrency)
            assertEquals(original.receiveCurrency, result.receiveCurrency)
        }
    }
}
