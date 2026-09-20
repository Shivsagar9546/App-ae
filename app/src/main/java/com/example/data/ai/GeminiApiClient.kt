package com.example.data.ai

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateContent(
        apiKeyOverride: String?,
        model: String,
        systemPrompt: String,
        messages: List<AiMessage>,
        imageInlineBase64: String? = null,
        imagesInlineBase64: List<String> = emptyList()
    ): AiResult = withContext(Dispatchers.IO) {
        val key = if (!apiKeyOverride.isNullOrBlank()) {
            apiKeyOverride
        } else {
            try {
                BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                ""
            }
        }

        if (key.isBlank() || key == "MY_GEMINI_API_KEY") {
            return@withContext AiResult.Error(
                "Gemini API key is not configured. Please configure it in the Admin Panel or through AI Studio Secrets.",
                isQuotaOrKeyError = true
            )
        }

        val allImages = when {
            imagesInlineBase64.isNotEmpty() -> imagesInlineBase64
            !imageInlineBase64.isNullOrBlank() -> listOf(imageInlineBase64)
            else -> emptyList()
        }

        // Fast priority models according to current Gemini API standards: gemini-1.5-flash is the speed king
        val requestedModel = if (model.isNotBlank()) model else "gemini-1.5-flash"
        val modelsToTry = mutableListOf<String>().apply {
            add(requestedModel)
            if (requestedModel != "gemini-1.5-flash") add("gemini-1.5-flash")
            if (requestedModel != "gemini-3.5-flash") add("gemini-3.5-flash")
            if (requestedModel != "gemini-flash-latest") add("gemini-flash-latest")
            if (requestedModel != "gemini-3.1-flash-lite-preview") add("gemini-3.1-flash-lite-preview")
        }.distinct()

        var lastErrorMsg = "Unable to reach Gemini servers."
        var isQuotaOrKey = false

        for (targetModel in modelsToTry) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$key"
            try {
                val rootJson = JSONObject()

                // System Instruction
                if (systemPrompt.isNotBlank()) {
                    val sysInst = JSONObject()
                    val sysParts = JSONArray()
                    sysParts.put(JSONObject().put("text", systemPrompt))
                    sysInst.put("parts", sysParts)
                    rootJson.put("systemInstruction", sysInst)
                }

                // Contents
                val contentsArray = JSONArray()
                messages.forEachIndexed { index, msg ->
                    val contentObj = JSONObject()
                    val isUser = msg.role.equals("user", ignoreCase = true)
                    contentObj.put("role", if (isUser) "user" else "model")

                    val partsArray = JSONArray()
                    if (msg.text.isNotBlank()) {
                        partsArray.put(JSONObject().put("text", msg.text))
                    }

                    // Attach images for this message
                    val messageImages = if (index == messages.lastIndex) {
                        if (allImages.isNotEmpty()) allImages
                        else if (!msg.imageBase64.isNullOrBlank()) listOf(msg.imageBase64)
                        else emptyList()
                    } else {
                        if (!msg.imageBase64.isNullOrBlank()) listOf(msg.imageBase64)
                        else emptyList()
                    }

                    val resolvedImages = messageImages.mapNotNull { imgData ->
                        if (imgData.isNotBlank() && (imgData.startsWith("/") || imgData.startsWith("file://"))) {
                            try {
                                val path = imgData.replace("file://", "")
                                val file = java.io.File(path)
                                if (file.exists()) {
                                    val bytes = file.readBytes()
                                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            imgData
                        }
                    }

                    resolvedImages.forEach { imgData ->
                        if (imgData.isNotBlank()) {
                            val inlineDataObj = JSONObject()
                            inlineDataObj.put("mimeType", "image/jpeg")
                            inlineDataObj.put("data", imgData)
                            partsArray.put(JSONObject().put("inlineData", inlineDataObj))
                        }
                    }

                    if (partsArray.length() > 0) {
                        contentObj.put("parts", partsArray)
                        contentsArray.put(contentObj)
                    }
                }

                // If empty, add default prompt
                if (contentsArray.length() == 0) {
                    val contentObj = JSONObject()
                    contentObj.put("role", "user")
                    val partsArray = JSONArray()
                    partsArray.put(JSONObject().put("text", "Hello"))
                    contentObj.put("parts", partsArray)
                    contentsArray.put(contentObj)
                }

                rootJson.put("contents", contentsArray)

                // Generation config: Low temperature for direct, fast and accurate responses
                val genConfig = JSONObject()
                genConfig.put("temperature", 0.1)
                genConfig.put("maxOutputTokens", 1024)

                rootJson.put("generationConfig", genConfig)

                val requestBody = rootJson.toString().toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseString = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val errorJson = JSONObject(responseString)
                        errorJson.optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}: $responseString"
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $responseString"
                    }
                    lastErrorMsg = errorMsg
                    isQuotaOrKey = response.code == 400 || response.code == 403

                    // If it's a high demand (503 / 429 / UNAVAILABLE / High Demand / Overloaded), attempt fallback model
                    val isHighDemandOrOverload = response.code == 503 || 
                            response.code == 429 || 
                            response.code == 500 || 
                            response.code == 502 || 
                            response.code == 504 || 
                            errorMsg.contains("high demand", ignoreCase = true) ||
                            errorMsg.contains("overloaded", ignoreCase = true) ||
                            errorMsg.contains("UNAVAILABLE", ignoreCase = true) ||
                            errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)

                    if (isHighDemandOrOverload && targetModel != modelsToTry.last()) {
                        Log.w("GeminiApiClient", "Model $targetModel is overloaded ($errorMsg). Trying next fallback model...")
                        kotlinx.coroutines.delay(200)
                        continue
                    }

                    return@withContext AiResult.Error(
                        errorMsg,
                        isQuotaOrKeyError = isQuotaOrKey || response.code == 429
                    )
                }

                val respJson = JSONObject(responseString)
                val candidates = respJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val textBuilder = StringBuilder()
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            textBuilder.append(part.optString("text", ""))
                        }
                    }
                    val text = textBuilder.toString()
                    if (text.isNotBlank()) {
                        return@withContext AiResult.Success(
                            text = text,
                            providerUsed = "Gemini",
                            modelUsed = targetModel
                        )
                    }
                }

                // If candidate was empty, try next model
                if (targetModel != modelsToTry.last()) {
                    continue
                }
                return@withContext AiResult.Error("No response generated from Gemini.")
            } catch (e: Exception) {
                Log.e("GeminiApiClient", "Generation error on $targetModel", e)
                lastErrorMsg = "Network error connecting to Gemini: ${e.localizedMessage ?: e.message}"
                if (targetModel != modelsToTry.last()) {
                    kotlinx.coroutines.delay(300)
                    continue
                }
            }
        }

        return@withContext AiResult.Error(lastErrorMsg, isQuotaOrKeyError = isQuotaOrKey)
    }

    suspend fun testConnection(apiKey: String, model: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val testPrompt = listOf(AiMessage(role = "user", text = "Hi! Please reply with 'Gemini connection successful'"))
        val result = generateContent(
            apiKeyOverride = apiKey,
            model = model,
            systemPrompt = "You are a test agent. Keep answer short.",
            messages = testPrompt
        )
        when (result) {
            is AiResult.Success -> Pair(true, "Success: ${result.text.take(80)}")
            is AiResult.Error -> Pair(false, result.message)
        }
    }
}
