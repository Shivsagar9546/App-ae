package com.example.data.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.data.preferences.AdminPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        imageBitmaps: List<Bitmap> = emptyList(),
        isScreenScan: Boolean = false,
        systemPromptOverride: String? = null
    ): AiResult = withContext(Dispatchers.IO) {
        val settings = adminPreferencesRepository.getSettings()

        // Check if screen scan is disabled by admin
        if (isScreenScan && !settings.isScreenScanEnabled) {
            return@withContext AiResult.Error("Screen scan has been disabled by the administrator in Admin Settings.")
        }

        // Collect all images (either single bitmap or list of bitmaps)
        val allBitmaps = when {
            imageBitmaps.isNotEmpty() -> imageBitmaps
            imageBitmap != null -> listOf(imageBitmap)
            else -> emptyList()
        }

        // Fast parallel image compression: 1024px maximum dimension with 75% JPEG quality
        // Reduces upload payload by ~95%, allowing instant transfer and ultra-fast Gemini OCR analysis
        val compressedImagesBase64 = if (allBitmaps.isNotEmpty()) {
            coroutineScope {
                allBitmaps.map { bmp ->
                    async(Dispatchers.Default) {
                        compressBitmapToBase64(bmp, settings.maxImageResolution)
                    }
                }.awaitAll()
            }
        } else {
            emptyList()
        }

        val firstImageBase64 = compressedImagesBase64.firstOrNull()
        val primaryProvider = settings.defaultProvider.lowercase()
        val sysPrompt = systemPromptOverride ?: settings.systemPrompt

        // Try primary provider
        val primaryResult = if (primaryProvider == "openai") {
            openAiApiClient.generateContent(
                apiKey = settings.openAiApiKey,
                model = settings.openAiModel,
                systemPrompt = sysPrompt,
                messages = messages,
                imageInlineBase64 = firstImageBase64
            )
        } else {
            geminiApiClient.generateContent(
                apiKeyOverride = settings.geminiApiKey.ifBlank { null },
                model = settings.geminiModel,
                systemPrompt = sysPrompt,
                messages = messages,
                imageInlineBase64 = firstImageBase64,
                imagesInlineBase64 = compressedImagesBase64
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
                    imageInlineBase64 = firstImageBase64,
                    imagesInlineBase64 = compressedImagesBase64
                )
            } else {
                // Fallback to OpenAI
                openAiApiClient.generateContent(
                    apiKey = settings.openAiApiKey,
                    model = settings.openAiModel,
                    systemPrompt = sysPrompt,
                    messages = messages,
                    imageInlineBase64 = firstImageBase64
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
        // Use 1024 maxDim for ultra-fast network transfer & low latency without sacrificing OCR clarity
        val targetMaxDim = if (maxDim in 480..1280) maxDim else 1024
        var scaledBitmap = bitmap
        val width = bitmap.width
        val height = bitmap.height

        if (width > targetMaxDim || height > targetMaxDim) {
            val ratio = width.toFloat() / height.toFloat()
            val newWidth: Int
            val newHeight: Int
            if (width > height) {
                newWidth = targetMaxDim
                newHeight = (targetMaxDim / ratio).toInt().coerceAtLeast(1)
            } else {
                newHeight = targetMaxDim
                newWidth = (targetMaxDim * ratio).toInt().coerceAtLeast(1)
            }
            scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }

        val outputStream = ByteArrayOutputStream()
        // 75% JPEG gives sharp OCR reading with tiny ~80KB payload for instant response
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        if (scaledBitmap != bitmap) {
            try {
                scaledBitmap.recycle()
            } catch (_: Exception) {}
        }
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    suspend fun testGemini(apiKey: String, model: String) = geminiApiClient.testConnection(apiKey, model)

    suspend fun testOpenAi(apiKey: String, model: String) = openAiApiClient.testConnection(apiKey, model)
}
