package com.swiftpay.transfer.service

import java.math.BigDecimal
import java.math.RoundingMode

interface FeeCalculator {
    fun calculateFee(sendAmount: BigDecimal): BigDecimal
}

class LegacyFeeCalculator(private val baseFee: BigDecimal) : FeeCalculator {
    override fun calculateFee(sendAmount: BigDecimal): BigDecimal = baseFee
}

class TieredFeeCalculator : FeeCalculator {

    companion object {
        private val MIN_FEE = BigDecimal("0.99")
        private val TIER1_LIMIT = BigDecimal("100")
        private val TIER2_LIMIT = BigDecimal("500")
        private val TIER1_RATE = BigDecimal("0.01")
        private val TIER2_RATE = BigDecimal("0.008")
        private val TIER3_RATE = BigDecimal("0.005")
    }

    override fun calculateFee(sendAmount: BigDecimal): BigDecimal {
        val fee = when {
            sendAmount <= TIER1_LIMIT ->
                sendAmount.multiply(TIER1_RATE)

            sendAmount <= TIER2_LIMIT ->
                TIER1_LIMIT.multiply(TIER1_RATE)
                    .add((sendAmount - TIER1_LIMIT).multiply(TIER2_RATE))

            else ->
                TIER1_LIMIT.multiply(TIER1_RATE)
                    .add((TIER2_LIMIT - TIER1_LIMIT).multiply(TIER2_RATE))
                    .add((sendAmount - TIER2_LIMIT).multiply(TIER3_RATE))
        }
        return fee.max(MIN_FEE).setScale(2, RoundingMode.HALF_UP)
    }
}
