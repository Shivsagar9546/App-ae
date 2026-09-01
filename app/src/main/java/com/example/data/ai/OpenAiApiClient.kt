package com.example.data.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateContent(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<AiMessage>,
        imageInlineBase64: String? = null
    ): AiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext AiResult.Error(
                "OpenAI API key is not configured. Please add your key in the Admin Panel.",
                isQuotaOrKeyError = true
            )
        }

        val targetModel = if (model.isNotBlank()) model else "gpt-4o-mini"
        val url = "https://api.openai.com/v1/chat/completions"

        try {
            val rootJson = JSONObject()
            rootJson.put("model", targetModel)

            val messagesArray = JSONArray()

            // System prompt
            if (systemPrompt.isNotBlank()) {
                val sysObj = JSONObject()
                sysObj.put("role", "system")
                sysObj.put("content", systemPrompt)
                messagesArray.put(sysObj)
            }

            // Chat messages
            messages.forEachIndexed { index, msg ->
                val msgObj = JSONObject()
                val role = when (msg.role.lowercase()) {
                    "user" -> "user"
                    "system" -> "system"
                    else -> "assistant"
                }
                msgObj.put("role", role)

                val imgData = if (index == messages.lastIndex) (imageInlineBase64 ?: msg.imageBase64) else msg.imageBase64

                if (!imgData.isNullOrBlank()) {
                    val contentArray = JSONArray()
                    if (msg.text.isNotBlank()) {
                        contentArray.put(JSONObject().put("type", "text").put("text", msg.text))
                    }
                    val imgObj = JSONObject()
                    imgObj.put("type", "image_url")
                    val urlObj = JSONObject()
                    urlObj.put("url", "data:image/jpeg;base64,$imgData")
                    imgObj.put("image_url", urlObj)
                    contentArray.put(imgObj)
                    msgObj.put("content", contentArray)
                } else {
                    msgObj.put("content", msg.text)
                }

                messagesArray.put(msgObj)
            }

            if (messagesArray.length() == 0) {
                messagesArray.put(JSONObject().put("role", "user").put("content", "Hello"))
            }

            rootJson.put("messages", messagesArray)
            rootJson.put("temperature", 0.7)

            val requestBody = rootJson.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseString = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val errJson = JSONObject(responseString)
                    errJson.optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}: $responseString"
                } catch (e: Exception) {
                    "HTTP ${response.code}: $responseString"
                }
                return@withContext AiResult.Error(
                    errorMsg,
                    isQuotaOrKeyError = response.code == 401 || response.code == 429
                )
            }

            val respJson = JSONObject(responseString)
            val choices = respJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val msg = firstChoice.optJSONObject("message")
                val text = msg?.optString("content", "") ?: ""
                if (text.isNotBlank()) {
                    return@withContext AiResult.Success(
                        text = text,
                        providerUsed = "OpenAI",
                        modelUsed = targetModel
                    )
                }
            }

            return@withContext AiResult.Error("No response content from OpenAI.")
        } catch (e: Exception) {
            Log.e("OpenAiApiClient", "Generation error", e)
            return@withContext AiResult.Error("Network error connecting to OpenAI: ${e.localizedMessage ?: e.message}")
        }
    }

    suspend fun testConnection(apiKey: String, model: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val testPrompt = listOf(AiMessage(role = "user", text = "Hi! Please reply with 'OpenAI connection successful'"))
        val result = generateContent(
            apiKey = apiKey,
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
