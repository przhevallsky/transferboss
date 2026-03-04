package com.transferhub.pricing.service

import com.transferhub.pricing.config.RedisClientFactory
import com.transferhub.pricing.model.Quote
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

class QuoteCacheService(
    private val redisClientFactory: RedisClientFactory,
    private val quoteTtlSeconds: Long,
) {

    private val logger = LoggerFactory.getLogger(QuoteCacheService::class.java)

    suspend fun save(quote: Quote) {
        val key = "quote:${quote.quoteId}"
        val cached = CachedQuote.fromDomain(quote)
        val json = Json.encodeToString(CachedQuote.serializer(), cached)
        redisClientFactory.asyncCommands.setex(key, quoteTtlSeconds, json).await()
        logger.debug("Cached quote {} with TTL {}s", quote.quoteId, quoteTtlSeconds)
    }

    suspend fun get(quoteId: String): Quote? {
        val key = "quote:$quoteId"
        val json = redisClientFactory.asyncCommands.get(key).await() ?: return null
        return try {
            val cached = Json.decodeFromString(CachedQuote.serializer(), json)
            cached.toDomain()
        } catch (e: Exception) {
            logger.warn("Failed to deserialize cached quote {}: {}", quoteId, e.message)
            null
        }
    }

    @Serializable
    private data class CachedQuote(
        val quoteId: String,
        val sourceCountry: String,
        val destinationCountry: String,
        val sendCurrency: String,
        val receiveCurrency: String,
        val sendAmount: String,
        val receiveAmount: String,
        val exchangeRate: String,
        val feeAmount: String,
        val feeCurrency: String,
        val deliveryMethod: String,
        val expiresAtEpochMs: Long,
        val senderId: String,
        val deliveryEstimateMinMinutes: Int,
        val deliveryEstimateMaxMinutes: Int,
    ) {
        fun toDomain(): Quote = Quote(
            quoteId = quoteId,
            sourceCountry = sourceCountry,
            destinationCountry = destinationCountry,
            sendCurrency = sendCurrency,
            receiveCurrency = receiveCurrency,
            sendAmount = BigDecimal(sendAmount),
            receiveAmount = BigDecimal(receiveAmount),
            exchangeRate = BigDecimal(exchangeRate),
            feeAmount = BigDecimal(feeAmount),
            feeCurrency = feeCurrency,
            deliveryMethod = deliveryMethod,
            expiresAt = Instant.ofEpochMilli(expiresAtEpochMs),
            senderId = senderId,
            deliveryEstimateMinMinutes = deliveryEstimateMinMinutes,
            deliveryEstimateMaxMinutes = deliveryEstimateMaxMinutes,
        )

        companion object {
            fun fromDomain(quote: Quote): CachedQuote = CachedQuote(
                quoteId = quote.quoteId,
                sourceCountry = quote.sourceCountry,
                destinationCountry = quote.destinationCountry,
                sendCurrency = quote.sendCurrency,
                receiveCurrency = quote.receiveCurrency,
                sendAmount = quote.sendAmount.toPlainString(),
                receiveAmount = quote.receiveAmount.toPlainString(),
                exchangeRate = quote.exchangeRate.toPlainString(),
                feeAmount = quote.feeAmount.toPlainString(),
                feeCurrency = quote.feeCurrency,
                deliveryMethod = quote.deliveryMethod,
                expiresAtEpochMs = quote.expiresAt.toEpochMilli(),
                senderId = quote.senderId,
                deliveryEstimateMinMinutes = quote.deliveryEstimateMinMinutes,
                deliveryEstimateMaxMinutes = quote.deliveryEstimateMaxMinutes,
            )
        }
    }
}
