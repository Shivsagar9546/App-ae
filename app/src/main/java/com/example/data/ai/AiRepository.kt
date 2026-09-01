package com.example.data.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.data.preferences.AdminPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class AiRepository(
    private val adminPreferencesRepository: AdminPreferencesRepository,
    private val geminiApiClient: GeminiApiClient = GeminiApiClient(),
    private val openAiApiClient: OpenAiApiClient = OpenAiApiClient()
) {

    suspend fun askAi(
        messages: List<AiMessage>,
        imageBitmap: Bitmap? = null,
        isScreenScan: Boolean = false,
        systemPromptOverride: String? = null
    ): AiResult = withContext(Dispatchers.IO) {
        val settings = adminPreferencesRepository.getSettings()

        // Check if screen scan is disabled by admin
        if (isScreenScan && !settings.isScreenScanEnabled) {
            return@withContext AiResult.Error("Screen scan has been disabled by the administrator in Admin Settings.")
        }

        // Compress image if present
        val imageBase64 = imageBitmap?.let { bitmap ->
            compressBitmapToBase64(bitmap, settings.maxImageResolution)
        }

        val primaryProvider = settings.defaultProvider.lowercase()
        val sysPrompt = systemPromptOverride ?: settings.systemPrompt

        // Try primary provider
        val primaryResult = if (primaryProvider == "openai") {
            openAiApiClient.generateContent(
                apiKey = settings.openAiApiKey,
                model = settings.openAiModel,
                systemPrompt = sysPrompt,
                messages = messages,
                imageInlineBase64 = imageBase64
            )
        } else {
            geminiApiClient.generateContent(
                apiKeyOverride = settings.geminiApiKey.ifBlank { null },
                model = settings.geminiModel,
                systemPrompt = sysPrompt,
                messages = messages,
                imageInlineBase64 = imageBase64
            )
        }

        if (primaryResult is AiResult.Success) {
            adminPreferencesRepository.recordRequest(primaryResult.providerUsed, isScreenScan)
            return@withContext primaryResult
        }

        // If primary failed and fallback is enabled, try the alternative
        if (settings.isFallbackEnabled) {
            val fallbackResult = if (primaryProvider == "openai") {
                // Fallback to Gemini
                geminiApiClient.generateContent(
                    apiKeyOverride = settings.geminiApiKey.ifBlank { null },
                    model = settings.geminiModel,
                    systemPrompt = sysPrompt,
                    messages = messages,
                    imageInlineBase64 = imageBase64
                )
            } else {
                // Fallback to OpenAI
                openAiApiClient.generateContent(
                    apiKey = settings.openAiApiKey,
                    model = settings.openAiModel,
                    systemPrompt = sysPrompt,
                    messages = messages,
                    imageInlineBase64 = imageBase64
                )
            }

            if (fallbackResult is AiResult.Success) {
                adminPreferencesRepository.recordRequest(fallbackResult.providerUsed, isScreenScan)
                return@withContext fallbackResult
            }
        }

        // If all failed, record error and return error
        adminPreferencesRepository.recordError()
        val errorMsg = (primaryResult as? AiResult.Error)?.message ?: "Unable to complete AI request."
        return@withContext AiResult.Error(errorMsg)
    }

    private fun compressBitmapToBase64(bitmap: Bitmap, maxDim: Int): String {
        var scaledBitmap = bitmap
        val width = bitmap.width
        val height = bitmap.height

        if (width > maxDim || height > maxDim) {
            val ratio = width.toFloat() / height.toFloat()
            val newWidth: Int
            val newHeight: Int
            if (width > height) {
                newWidth = maxDim
                newHeight = (maxDim / ratio).toInt()
            } else {
                newHeight = maxDim
                newWidth = (maxDim * ratio).toInt()
            }
            scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    suspend fun testGemini(apiKey: String, model: String) = geminiApiClient.testConnection(apiKey, model)

    suspend fun testOpenAi(apiKey: String, model: String) = openAiApiClient.testConnection(apiKey, model)
}
