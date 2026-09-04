package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "admin_settings")

data class AdminSettings(
    val defaultProvider: String = "gemini", // "gemini" or "openai"
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-3.5-flash",
    val isGeminiEnabled: Boolean = true,
    val openAiApiKey: String = "",
    val openAiModel: String = "gpt-4o-mini",
    val isOpenAiEnabled: Boolean = true,
    val isFallbackEnabled: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val isScreenScanEnabled: Boolean = true,
    val isAreaScanEnabled: Boolean = true,
    val maxImageResolution: Int = 1920,
    val adminPin: String = "1234",
    val appTheme: String = "system", // "system", "dark", "light"
    val preferredLanguage: String = "hinglish", // "en", "hi", "hinglish"
    val totalRequests: Int = 0,
    val todayRequests: Int = 0,
    val geminiRequests: Int = 0,
    val openAiRequests: Int = 0,
    val screenScanRequests: Int = 0,
    val errorCount: Int = 0,
    val lastRequestDate: String = "",
    val bubbleStyle: String = "pill", // "pill", "circle", "icon_only", "square"
    val bubbleCustomImagePath: String = "",
    val bubblePresetIcon: String = "sparkle", // "sparkle", "robot", "brain", "flash", "target", "flame", "diamond", "eye"
    val bubbleText: String = "AI ✨",
    val bubbleGradient: String = "purple", // "purple", "cyan", "sunset", "emerald", "dark", "gold"
    val bubbleSize: String = "medium", // "small", "medium", "large"
    val bubbleAlpha: Float = 1.0f
) {
    fun maskKey(key: String): String {
        if (key.isBlank()) return "Not configured"
        if (key.length <= 8) return "••••••••"
        val prefix = key.take(4)
        val suffix = key.takeLast(4)
        return "$prefix••••••••$suffix"
    }
}

const val DEFAULT_SYSTEM_PROMPT = """You are OmniAI, a personal and versatile AI assistant. 
You can understand and answer questions from screen scans, screenshots, images, code, and text.
You support English, Hindi, and Hinglish naturally (e.g. answering mixed Hindi-English queries fluently).
Be concise, clear, helpful, and accurate. When explaining solutions or screen scans, give direct answers first, followed by clear explanations."""

class AdminPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val DEFAULT_PROVIDER = stringPreferencesKey("default_provider")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val GEMINI_ENABLED = booleanPreferencesKey("gemini_enabled")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val OPENAI_ENABLED = booleanPreferencesKey("openai_enabled")
        val FALLBACK_ENABLED = booleanPreferencesKey("fallback_enabled")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val SCREEN_SCAN_ENABLED = booleanPreferencesKey("screen_scan_enabled")
        val AREA_SCAN_ENABLED = booleanPreferencesKey("area_scan_enabled")
        val MAX_IMAGE_RESOLUTION = intPreferencesKey("max_image_resolution")
        val ADMIN_PIN = stringPreferencesKey("admin_pin")
        val APP_THEME = stringPreferencesKey("app_theme")
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")

        val BUBBLE_STYLE = stringPreferencesKey("bubble_style")
        val BUBBLE_CUSTOM_IMAGE_PATH = stringPreferencesKey("bubble_custom_image_path")
        val BUBBLE_PRESET_ICON = stringPreferencesKey("bubble_preset_icon")
        val BUBBLE_TEXT = stringPreferencesKey("bubble_text")
        val BUBBLE_GRADIENT = stringPreferencesKey("bubble_gradient")
        val BUBBLE_SIZE = stringPreferencesKey("bubble_size")
        val BUBBLE_ALPHA = androidx.datastore.preferences.core.floatPreferencesKey("bubble_alpha")

        val TOTAL_REQUESTS = intPreferencesKey("total_requests")
        val TODAY_REQUESTS = intPreferencesKey("today_requests")
        val GEMINI_REQUESTS = intPreferencesKey("gemini_requests")
        val OPENAI_REQUESTS = intPreferencesKey("openai_requests")
        val SCREEN_SCAN_REQUESTS = intPreferencesKey("screen_scan_requests")
        val ERROR_COUNT = intPreferencesKey("error_count")
        val LAST_REQUEST_DATE = stringPreferencesKey("last_request_date")
    }

    val settingsFlow: Flow<AdminSettings> = context.dataStore.data.map { preferences ->
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val storedDate = preferences[PreferencesKeys.LAST_REQUEST_DATE] ?: today
        val todayCount = if (storedDate == today) {
            preferences[PreferencesKeys.TODAY_REQUESTS] ?: 0
        } else {
            0
        }

        AdminSettings(
            defaultProvider = preferences[PreferencesKeys.DEFAULT_PROVIDER] ?: "gemini",
            geminiApiKey = preferences[PreferencesKeys.GEMINI_API_KEY] ?: "",
            geminiModel = preferences[PreferencesKeys.GEMINI_MODEL] ?: "gemini-3.5-flash",
            isGeminiEnabled = preferences[PreferencesKeys.GEMINI_ENABLED] ?: true,
            openAiApiKey = preferences[PreferencesKeys.OPENAI_API_KEY] ?: "",
            openAiModel = preferences[PreferencesKeys.OPENAI_MODEL] ?: "gpt-4o-mini",
            isOpenAiEnabled = preferences[PreferencesKeys.OPENAI_ENABLED] ?: true,
            isFallbackEnabled = preferences[PreferencesKeys.FALLBACK_ENABLED] ?: true,
            systemPrompt = preferences[PreferencesKeys.SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
            isScreenScanEnabled = preferences[PreferencesKeys.SCREEN_SCAN_ENABLED] ?: true,
            isAreaScanEnabled = preferences[PreferencesKeys.AREA_SCAN_ENABLED] ?: true,
            maxImageResolution = preferences[PreferencesKeys.MAX_IMAGE_RESOLUTION] ?: 1920,
            adminPin = preferences[PreferencesKeys.ADMIN_PIN] ?: "1234",
            appTheme = preferences[PreferencesKeys.APP_THEME] ?: "system",
            preferredLanguage = preferences[PreferencesKeys.PREFERRED_LANGUAGE] ?: "hinglish",
            bubbleStyle = preferences[PreferencesKeys.BUBBLE_STYLE] ?: "pill",
            bubbleCustomImagePath = preferences[PreferencesKeys.BUBBLE_CUSTOM_IMAGE_PATH] ?: "",
            bubblePresetIcon = preferences[PreferencesKeys.BUBBLE_PRESET_ICON] ?: "sparkle",
            bubbleText = preferences[PreferencesKeys.BUBBLE_TEXT] ?: "AI ✨",
            bubbleGradient = preferences[PreferencesKeys.BUBBLE_GRADIENT] ?: "purple",
            bubbleSize = preferences[PreferencesKeys.BUBBLE_SIZE] ?: "medium",
            bubbleAlpha = preferences[PreferencesKeys.BUBBLE_ALPHA] ?: 1.0f,
            totalRequests = preferences[PreferencesKeys.TOTAL_REQUESTS] ?: 0,
            todayRequests = todayCount,
            geminiRequests = preferences[PreferencesKeys.GEMINI_REQUESTS] ?: 0,
            openAiRequests = preferences[PreferencesKeys.OPENAI_REQUESTS] ?: 0,
            screenScanRequests = preferences[PreferencesKeys.SCREEN_SCAN_REQUESTS] ?: 0,
            errorCount = preferences[PreferencesKeys.ERROR_COUNT] ?: 0,
            lastRequestDate = storedDate
        )
    }

    suspend fun getSettings(): AdminSettings = settingsFlow.first()

    suspend fun updateSettings(
        defaultProvider: String? = null,
        geminiApiKey: String? = null,
        geminiModel: String? = null,
        isGeminiEnabled: Boolean? = null,
        openAiApiKey: String? = null,
        openAiModel: String? = null,
        isOpenAiEnabled: Boolean? = null,
        isFallbackEnabled: Boolean? = null,
        systemPrompt: String? = null,
        isScreenScanEnabled: Boolean? = null,
        isAreaScanEnabled: Boolean? = null,
        maxImageResolution: Int? = null,
        adminPin: String? = null,
        appTheme: String? = null,
        preferredLanguage: String? = null,
        bubbleStyle: String? = null,
        bubbleCustomImagePath: String? = null,
        bubblePresetIcon: String? = null,
        bubbleText: String? = null,
        bubbleGradient: String? = null,
        bubbleSize: String? = null,
        bubbleAlpha: Float? = null
    ) {
        context.dataStore.edit { preferences ->
            defaultProvider?.let { preferences[PreferencesKeys.DEFAULT_PROVIDER] = it }
            geminiApiKey?.let { preferences[PreferencesKeys.GEMINI_API_KEY] = it }
            geminiModel?.let { preferences[PreferencesKeys.GEMINI_MODEL] = it }
            isGeminiEnabled?.let { preferences[PreferencesKeys.GEMINI_ENABLED] = it }
            openAiApiKey?.let { preferences[PreferencesKeys.OPENAI_API_KEY] = it }
            openAiModel?.let { preferences[PreferencesKeys.OPENAI_MODEL] = it }
            isOpenAiEnabled?.let { preferences[PreferencesKeys.OPENAI_ENABLED] = it }
            isFallbackEnabled?.let { preferences[PreferencesKeys.FALLBACK_ENABLED] = it }
            systemPrompt?.let { preferences[PreferencesKeys.SYSTEM_PROMPT] = it }
            isScreenScanEnabled?.let { preferences[PreferencesKeys.SCREEN_SCAN_ENABLED] = it }
            isAreaScanEnabled?.let { preferences[PreferencesKeys.AREA_SCAN_ENABLED] = it }
            maxImageResolution?.let { preferences[PreferencesKeys.MAX_IMAGE_RESOLUTION] = it }
            adminPin?.let { preferences[PreferencesKeys.ADMIN_PIN] = it }
            appTheme?.let { preferences[PreferencesKeys.APP_THEME] = it }
            preferredLanguage?.let { preferences[PreferencesKeys.PREFERRED_LANGUAGE] = it }
            bubbleStyle?.let { preferences[PreferencesKeys.BUBBLE_STYLE] = it }
            bubbleCustomImagePath?.let { preferences[PreferencesKeys.BUBBLE_CUSTOM_IMAGE_PATH] = it }
            bubblePresetIcon?.let { preferences[PreferencesKeys.BUBBLE_PRESET_ICON] = it }
            bubbleText?.let { preferences[PreferencesKeys.BUBBLE_TEXT] = it }
            bubbleGradient?.let { preferences[PreferencesKeys.BUBBLE_GRADIENT] = it }
            bubbleSize?.let { preferences[PreferencesKeys.BUBBLE_SIZE] = it }
            bubbleAlpha?.let { preferences[PreferencesKeys.BUBBLE_ALPHA] = it }
        }
    }

    suspend fun recordRequest(provider: String, isScreenScan: Boolean) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        context.dataStore.edit { preferences ->
            val storedDate = preferences[PreferencesKeys.LAST_REQUEST_DATE] ?: ""
            val todayCount = if (storedDate == today) {
                (preferences[PreferencesKeys.TODAY_REQUESTS] ?: 0) + 1
            } else {
                1
            }
            preferences[PreferencesKeys.LAST_REQUEST_DATE] = today
            preferences[PreferencesKeys.TODAY_REQUESTS] = todayCount
            preferences[PreferencesKeys.TOTAL_REQUESTS] = (preferences[PreferencesKeys.TOTAL_REQUESTS] ?: 0) + 1

            if (provider.equals("gemini", ignoreCase = true)) {
                preferences[PreferencesKeys.GEMINI_REQUESTS] = (preferences[PreferencesKeys.GEMINI_REQUESTS] ?: 0) + 1
            } else if (provider.equals("openai", ignoreCase = true)) {
                preferences[PreferencesKeys.OPENAI_REQUESTS] = (preferences[PreferencesKeys.OPENAI_REQUESTS] ?: 0) + 1
            }

            if (isScreenScan) {
                preferences[PreferencesKeys.SCREEN_SCAN_REQUESTS] = (preferences[PreferencesKeys.SCREEN_SCAN_REQUESTS] ?: 0) + 1
            }
        }
    }

    suspend fun recordError() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ERROR_COUNT] = (preferences[PreferencesKeys.ERROR_COUNT] ?: 0) + 1
        }
    }

    suspend fun resetStats() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TOTAL_REQUESTS] = 0
            preferences[PreferencesKeys.TODAY_REQUESTS] = 0
            preferences[PreferencesKeys.GEMINI_REQUESTS] = 0
            preferences[PreferencesKeys.OPENAI_REQUESTS] = 0
            preferences[PreferencesKeys.SCREEN_SCAN_REQUESTS] = 0
            preferences[PreferencesKeys.ERROR_COUNT] = 0
        }
    }
}
