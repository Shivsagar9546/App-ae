package com.example.data.ai

data class AiMessage(
    val role: String, // "user", "model", "assistant", "system"
    val text: String,
    val imageBase64: String? = null // JPEG Base64
)

sealed class AiResult {
    data class Success(val text: String, val providerUsed: String, val modelUsed: String) : AiResult()
    data class Error(val message: String, val isQuotaOrKeyError: Boolean = false) : AiResult()
}
