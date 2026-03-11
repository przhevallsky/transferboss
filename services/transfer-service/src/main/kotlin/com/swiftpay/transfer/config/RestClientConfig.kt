package com.swiftpay.transfer.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfig(
    @Value("\${identity.base-url}") private val identityBaseUrl: String
) {

    @Bean
    fun identityRestClient(): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(3000)
            setReadTimeout(5000)
        }
        return RestClient.builder()
            .baseUrl(identityBaseUrl)
            .requestFactory(factory)
            .build()
    }
}
