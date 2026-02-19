package com.transferhub.pricing.config

data class AppConfig(
    val server: ServerConfig,
    val grpc: GrpcConfig,
    val redis: RedisConfig,
    val mongodb: MongoDbConfig,
) {
    companion object {
        fun load(): AppConfig = AppConfig(
            server = ServerConfig(
                port = envOrDefault("SERVER_PORT", "8082").toInt()
            ),
            grpc = GrpcConfig(
                port = envOrDefault("GRPC_PORT", "9090").toInt()
            ),
            redis = RedisConfig(
                host = envOrDefault("REDIS_HOST", "localhost"),
                port = envOrDefault("REDIS_PORT", "6379").toInt(),
            ),
            mongodb = MongoDbConfig(
                uri = envOrDefault("MONGODB_URI", "mongodb://localhost:27017"),
                database = envOrDefault("MONGODB_DATABASE", "pricing"),
            ),
        )

        private fun envOrDefault(name: String, default: String): String =
            System.getenv(name) ?: default
    }
}

data class ServerConfig(val port: Int)
data class GrpcConfig(val port: Int)
data class RedisConfig(val host: String, val port: Int)
data class MongoDbConfig(val uri: String, val database: String)
