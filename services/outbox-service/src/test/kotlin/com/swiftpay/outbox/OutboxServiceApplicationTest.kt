package com.swiftpay.outbox

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus

class OutboxServiceApplicationTest : IntegrationTestBase() {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `application context loads and health endpoint returns UP`() {
        val response = restTemplate.getForEntity("/actuator/health", String::class.java)
        response.statusCode shouldBe HttpStatus.OK
    }
}
