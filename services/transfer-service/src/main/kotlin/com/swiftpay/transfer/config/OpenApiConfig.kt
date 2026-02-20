package com.swiftpay.transfer.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("TransferHub — Transfer Service API")
                .version("v1")
                .description("REST API for creating and managing international money transfers")
                .contact(Contact().name("Transfer Team").email("transfer-team@transferhub.com"))
        )
        .servers(
            listOf(
                Server().url("http://localhost:8080").description("Local development"),
                Server().url("https://api.staging.transferhub.com").description("Staging")
            )
        )
}
