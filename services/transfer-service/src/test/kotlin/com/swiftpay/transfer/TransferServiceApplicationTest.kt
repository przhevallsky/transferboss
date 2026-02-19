package com.swiftpay.transfer

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.HttpStatus

class TransferServiceApplicationTest : IntegrationTestBase() {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `context loads`() {
        // If we get here, the Spring context loaded successfully
    }

    @Test
    fun `actuator health returns UP`() {
        val response = restTemplate.getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertTrue(
            response.body!!.contains("\"status\":\"UP\""),
            "Expected health status UP but got: ${response.body}"
        )
    }
}
