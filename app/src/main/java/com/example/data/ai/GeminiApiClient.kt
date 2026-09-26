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
        imagesInlineBase64: List<String> = emptyList(),
        isWebSearchEnabled: Boolean = false
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
            if (requestedModel != "gemini-1.5-pro") add("gemini-1.5-pro")
            if (requestedModel != "gemini-2.0-flash-exp") add("gemini-2.0-flash-exp")
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

                    val resolvedImages = messageImages.flatMap { imgData ->
                        if (imgData.contains("|")) {
                            imgData.split("|")
                        } else {
                            listOf(imgData)
                        }
                    }.mapNotNull { imgData ->
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

                // If web search is enabled, add googleSearchRetrieval tool for real-time grounding
                if (isWebSearchEnabled) {
                    val toolsArray = JSONArray()
                    val googleSearchObj = JSONObject()
                    googleSearchObj.put("googleSearchRetrieval", JSONObject())
                    toolsArray.put(googleSearchObj)
                    rootJson.put("tools", toolsArray)
                }

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
                    
                    val isKeyError = response.code == 401 || response.code == 403
                    val isQuotaError = response.code == 429
                    isQuotaOrKey = isKeyError || isQuotaError

                    // Try next model if this is a model error (like 404/400) or high demand/overload
                    if (!isKeyError && !isQuotaError && targetModel != modelsToTry.last()) {
                        Log.w("GeminiApiClient", "Model $targetModel failed ($errorMsg). Trying next fallback model...")
                        kotlinx.coroutines.delay(200)
                        continue
                    }

                    return@withContext AiResult.Error(
                        errorMsg,
                        isQuotaOrKeyError = isQuotaOrKey
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
                    var text = textBuilder.toString()
                    if (text.isNotBlank()) {
                        // Parse Grounding Metadata for real-time web search links
                        val groundingMetadata = firstCandidate.optJSONObject("groundingMetadata")
                        if (groundingMetadata != null) {
                            val groundingChunks = groundingMetadata.optJSONArray("groundingChunks")
                            if (groundingChunks != null && groundingChunks.length() > 0) {
                                val sourcesList = mutableListOf<String>()
                                for (j in 0 until groundingChunks.length()) {
                                    val chunk = groundingChunks.getJSONObject(j)
                                    val web = chunk.optJSONObject("web")
                                    if (web != null) {
                                        val title = web.optString("title", "")
                                        val uri = web.optString("uri", "")
                                        if (uri.isNotBlank()) {
                                            val displayTitle = if (title.isNotBlank()) title else uri
                                            sourcesList.add("- [$displayTitle]($uri)")
                                        }
                                    }
                                }
                                if (sourcesList.isNotEmpty()) {
                                    val distinctSources = sourcesList.distinct().take(5)
                                    text += "\n\n🌐 **Web Search Sources:**\n" + distinctSources.joinToString("\n")
                                }
                            }
                        }

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
