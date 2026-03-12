package com.swiftpay.llm.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("dev")
class MockLlmClient : LlmClient {

    private val log = LoggerFactory.getLogger(MockLlmClient::class.java)

    private val cannedResponses = mapOf(
        "transfer" to "To send a money transfer, log in to your account, select 'Send Money', " +
            "enter the recipient details, choose the amount and delivery method, and confirm the transaction. " +
            "You will receive a confirmation with a tracking number.",
        "fee" to "Our fee structure is competitive and transparent. Fees depend on the corridor, " +
            "amount, and delivery method. Typical fees range from 1.99 to 9.99 USD per transaction.",
        "compliance" to "We comply with all applicable regulations including KYC (Know Your Customer) " +
            "and AML (Anti-Money Laundering) requirements. You may be asked to verify your identity " +
            "with a government-issued ID.",
        "default" to "Thank you for your question. I can help with information about money transfers, " +
            "fees, delivery methods, compliance requirements, and more. Please ask a specific question " +
            "and I'll provide detailed assistance."
    )

    override suspend fun generateCompletion(prompt: String, systemPrompt: String?): String {
        log.info("MockLlmClient generating completion for prompt: {}", prompt.take(50))
        delay(200) // Simulate API latency
        return selectResponse(prompt)
    }

    override suspend fun generateCompletionStream(prompt: String, systemPrompt: String?): Flow<String> {
        log.info("MockLlmClient generating streaming completion for prompt: {}", prompt.take(50))
        val response = selectResponse(prompt)
        val words = response.split(" ")

        return flow {
            for (word in words) {
                emit("$word ")
                delay(50) // Simulate token-by-token streaming
            }
        }
    }

    private fun selectResponse(prompt: String): String {
        val lowerPrompt = prompt.lowercase()
        return when {
            lowerPrompt.contains("transfer") || lowerPrompt.contains("send") -> cannedResponses["transfer"]!!
            lowerPrompt.contains("fee") || lowerPrompt.contains("cost") || lowerPrompt.contains("price") ->
                cannedResponses["fee"]!!
            lowerPrompt.contains("compliance") || lowerPrompt.contains("kyc") || lowerPrompt.contains("aml") ->
                cannedResponses["compliance"]!!
            else -> cannedResponses["default"]!!
        }
    }
}
