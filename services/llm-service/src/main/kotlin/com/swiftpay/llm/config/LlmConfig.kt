package com.swiftpay.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@ConfigurationProperties(prefix = "llm.openai")
data class OpenAiProperties(
    val apiKey: String = "sk-mock-key",
    val model: String = "gpt-4",
    val baseUrl: String = "https://api.openai.com",
    val maxTokens: Int = 1024,
    val temperature: Double = 0.7
)

@Configuration
@EnableConfigurationProperties(OpenAiProperties::class)
class LlmConfig {

    @Bean
    fun openAiWebClient(properties: OpenAiProperties): WebClient {
        return WebClient.builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader("Authorization", "Bearer ${properties.apiKey}")
            .defaultHeader("Content-Type", "application/json")
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)
            }
            .build()
    }
}
