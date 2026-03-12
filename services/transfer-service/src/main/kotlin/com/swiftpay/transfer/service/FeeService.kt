package com.swiftpay.transfer.service

import com.swiftpay.transfer.client.QuoteData
import io.getunleash.Unleash
import io.getunleash.UnleashContext
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.util.UUID

@Service
class FeeService(
    private val unleash: Unleash,
    private val meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(FeeService::class.java)
    private val tieredCalculator = TieredFeeCalculator()

    fun applyFeeStrategy(senderId: UUID, quoteData: QuoteData): QuoteData {
        val context = UnleashContext.builder()
            .userId(senderId.toString())
            .build()

        val useNewAlgorithm = unleash.isEnabled("new-pricing-algorithm", context)
        val algorithmTag = if (useNewAlgorithm) "tiered" else "legacy"

        meterRegistry.counter("pricing.fee.calculation.total", "algorithm", algorithmTag).increment()

        if (!useNewAlgorithm) {
            return quoteData
        }

        val newFee = tieredCalculator.calculateFee(quoteData.sendAmount)
        val newReceiveAmount = (quoteData.sendAmount - newFee)
            .multiply(quoteData.exchangeRate)
            .setScale(2, RoundingMode.HALF_UP)

        log.info(
            "Tiered pricing applied for sender {}: fee {} -> {}, receive {} -> {}",
            senderId, quoteData.feeAmount, newFee, quoteData.receiveAmount, newReceiveAmount
        )

        return quoteData.copy(
            feeAmount = newFee,
            receiveAmount = newReceiveAmount
        )
    }
}
