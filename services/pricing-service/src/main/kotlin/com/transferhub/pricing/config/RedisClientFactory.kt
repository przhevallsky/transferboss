package com.transferhub.pricing.config

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import org.slf4j.LoggerFactory

class RedisClientFactory(private val redisConfig: RedisConfig) {

    private val logger = LoggerFactory.getLogger(RedisClientFactory::class.java)

    private lateinit var client: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>

    val asyncCommands: RedisAsyncCommands<String, String>
        get() = connection.async()

    fun connect() {
        val uri = RedisURI.builder()
            .withHost(redisConfig.host)
            .withPort(redisConfig.port)
            .build()
        client = RedisClient.create(uri)
        connection = client.connect()
        logger.info("Connected to Redis at {}:{}", redisConfig.host, redisConfig.port)
    }

    fun close() {
        logger.info("Closing Redis connection")
        if (::connection.isInitialized) connection.close()
        if (::client.isInitialized) client.shutdown()
    }
}
