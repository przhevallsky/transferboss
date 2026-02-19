package com.transferhub.pricing

import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.test.Test

class HealthRouteTest : IntegrationTestBase() {

    @Test
    fun `liveness probe returns 200 OK`() = testApp {
        val response = client.get("/health/live")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "OK"
    }

    @Test
    fun `readiness probe returns 200 OK`() = testApp {
        val response = client.get("/health/ready")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "OK"
    }

    @Test
    fun `metrics endpoint returns prometheus format`() = testApp {
        val response = client.get("/metrics")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText().contains("ktor_http") shouldBe true
    }

    @Test
    fun `corridors endpoint returns mock data`() = testApp {
        val response = client.get("/api/v1/corridors")

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText().contains("GB") shouldBe true
    }

    @Test
    fun `unknown route returns 404 with Problem Detail`() = testApp {
        val response = client.get("/api/v1/nonexistent")

        response.status shouldBe HttpStatusCode.NotFound
        response.bodyAsText().contains("Not Found") shouldBe true
    }
}
