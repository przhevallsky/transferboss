package com.swiftpay.llm.model.dto

import jakarta.validation.constraints.NotBlank

data class AskRequest(
    @field:NotBlank(message = "Question must not be blank")
    val question: String,
    val category: String? = null
)
