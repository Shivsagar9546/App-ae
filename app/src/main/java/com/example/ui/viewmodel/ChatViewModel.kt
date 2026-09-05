package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.OmniAIApplication
import com.example.data.ai.AiMessage
import com.example.data.ai.AiRepository
import com.example.data.ai.AiResult
import com.example.data.local.ChatMessage
import com.example.data.local.Conversation
import com.example.data.preferences.AdminSettings
import com.example.service.ScreenCaptureHelper
import com.example.service.ScreenCapturePermissionActivity
import com.example.service.VoiceRecognitionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OmniAIApplication
    private val chatDao = app.database.chatDao()
    private val adminPrefs = app.adminPreferences
    private val aiRepository = AiRepository(adminPrefs)
    private val screenCaptureHelper = ScreenCaptureHelper(application)
    val voiceHelper = VoiceRecognitionHelper(application)

    val adminSettings: StateFlow<AdminSettings> = adminPrefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AdminSettings())

    private val _currentConversationId = MutableStateFlow(UUID.randomUUID().toString())
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = _searchQuery.flatMapLatest { query ->
        if (query.isBlank()) chatDao.getAllConversations() else chatDao.searchConversations(query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentMessages: StateFlow<List<ChatMessage>> = _currentConversationId.flatMapLatest { id ->
        chatDao.getMessagesForConversation(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _attachedBitmap = MutableStateFlow<Bitmap?>(null)
    val attachedBitmap: StateFlow<Bitmap?> = _attachedBitmap.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isAdminUnlocked = MutableStateFlow(false)
    val isAdminUnlocked: StateFlow<Boolean> = _isAdminUnlocked.asStateFlow()

    private val _testApiResult = MutableStateFlow<Pair<String, Boolean>?>(null)
    val testApiResult: StateFlow<Pair<String, Boolean>?> = _testApiResult.asStateFlow()

    private var generationJob: Job? = null

    fun startNewChat() {
        stopGeneration()
        _attachedBitmap.value = null
        _currentConversationId.value = UUID.randomUUID().toString()
    }

    fun selectConversation(id: String) {
        stopGeneration()
        _attachedBitmap.value = null
        _currentConversationId.value = id
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun attachImageUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                _attachedBitmap.value = bitmap
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun setAttachedBitmap(bitmap: Bitmap?) {
        _attachedBitmap.value = bitmap
    }

    fun clearAttachedBitmap() {
        _attachedBitmap.value = null
    }

    fun sendMessage(promptText: String, isScan: Boolean = false) {
        if (promptText.isBlank() && _attachedBitmap.value == null) return

        val text = promptText.trim()
        val image = _attachedBitmap.value
        _attachedBitmap.value = null

        val convId = _currentConversationId.value

        // Convert attached image to base64 for persistent chat history display
        val imageBase64 = image?.let { bmp ->
            try {
                val maxDim = 800
                var scaled = bmp
                if (bmp.width > maxDim || bmp.height > maxDim) {
                    val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                    val newW = if (bmp.width > bmp.height) maxDim else (maxDim * ratio).toInt()
                    val newH = if (bmp.width > bmp.height) (maxDim / ratio).toInt() else maxDim
                    scaled = Bitmap.createScaledBitmap(bmp, newW.coerceAtLeast(1), newH.coerceAtLeast(1), true)
                }
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                null
            }
        }

        val userMessage = ChatMessage(
            conversationId = convId,
            role = "user",
            text = text,
            imageBase64 = imageBase64,
            isScreenScan = isScan,
            timestamp = System.currentTimeMillis()
        )

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _isGenerating.value = true

            // Ensure conversation row exists in DB
            val existing = chatDao.getConversationById(convId)
            if (existing == null) {
                chatDao.insertConversation(
                    Conversation(
                        id = convId,
                        title = if (text.isNotBlank()) text.take(32) else "Image Query",
                        updatedAt = System.currentTimeMillis(),
                        lastMessagePreview = text.ifBlank { "Image analysis" }
                    )
                )
            } else {
                chatDao.updateConversation(
                    existing.copy(
                        updatedAt = System.currentTimeMillis(),
                        lastMessagePreview = text.ifBlank { "Image analysis" }
                    )
                )
            }

            // Insert user message
            chatDao.insertMessage(userMessage)

            // Gather context history with imageBase64 preservation
            val history = chatDao.getMessagesList(convId)
            val aiMessages = history.map {
                AiMessage(role = it.role, text = it.text, imageBase64 = it.imageBase64)
            }

            val result = aiRepository.askAi(
                messages = aiMessages,
                imageBitmap = image,
                isScreenScan = isScan
            )

            _isGenerating.value = false
            _statusMessage.value = null

            when (result) {
                is AiResult.Success -> {
                    val modelMessage = ChatMessage(
                        conversationId = convId,
                        role = "model",
                        text = result.text,
                        timestamp = System.currentTimeMillis()
                    )
                    chatDao.insertMessage(modelMessage)
                }
                is AiResult.Error -> {
                    val errorMessage = ChatMessage(
                        conversationId = convId,
                        role = "model",
                        text = "⚠️ ${result.message}",
                        isError = true,
                        timestamp = System.currentTimeMillis()
                    )
                    chatDao.insertMessage(errorMessage)
                }
            }
        }
    }

    fun regenerateLastResponse() {
        val convId = _currentConversationId.value
        viewModelScope.launch {
            val messages = chatDao.getMessagesList(convId)
            if (messages.isEmpty()) return@launch

            val lastUserMsg = messages.lastOrNull { it.role == "user" } ?: return@launch
            // Delete subsequent model message if any
            val lastMsg = messages.last()
            if (lastMsg.role == "model") {
                chatDao.deleteMessageById(lastMsg.id)
            }

            _isGenerating.value = true
            val updatedList = chatDao.getMessagesList(convId)
            val aiMessages = updatedList.map { AiMessage(role = it.role, text = it.text, imageBase64 = it.imageBase64) }

            val result = aiRepository.askAi(
                messages = aiMessages,
                isScreenScan = lastUserMsg.isScreenScan
            )

            _isGenerating.value = false
            when (result) {
                is AiResult.Success -> {
                    chatDao.insertMessage(
                        ChatMessage(
                            conversationId = convId,
                            role = "model",
                            text = result.text,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                is AiResult.Error -> {
                    chatDao.insertMessage(
                        ChatMessage(
                            conversationId = convId,
                            role = "model",
                            text = "⚠️ ${result.message}",
                            isError = true,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _isGenerating.value = false
        _statusMessage.value = null
    }

    fun triggerScreenScan(context: Context, cropRect: Rect? = null) {
        _statusMessage.value = "Requesting Screen Permission..."
        ScreenCapturePermissionActivity.requestPermission(
            context = context,
            onGranted = { resultCode, data ->
                viewModelScope.launch {
                    _statusMessage.value = "Capturing screen frame..."
                    val bitmap = screenCaptureHelper.captureFrame(resultCode, data, cropRect)
                    if (bitmap != null) {
                        _attachedBitmap.value = bitmap
                        _statusMessage.value = null
                        sendMessage("Scan and analyze this screen.", isScan = true)
                    } else {
                        _statusMessage.value = null
                        Toast.makeText(context, "Screen capture failed or timed out", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDenied = {
                _statusMessage.value = null
                Toast.makeText(context, "Screen capture permission was denied", Toast.LENGTH_SHORT).show()
            }
        )
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            chatDao.renameConversation(id, newTitle)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatDao.deleteConversation(id)
            if (_currentConversationId.value == id) {
                startNewChat()
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            chatDao.clearAllConversations()
            startNewChat()
        }
    }

    // Admin Panel methods
    fun verifyAdminPin(pin: String): Boolean {
        val currentPin = adminSettings.value.adminPin
        val success = pin == currentPin
        _isAdminUnlocked.value = success
        return success
    }

    fun lockAdmin() {
        _isAdminUnlocked.value = false
    }

    fun updateAdminSettings(
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
        viewModelScope.launch {
            adminPrefs.updateSettings(
                defaultProvider = defaultProvider,
                geminiApiKey = geminiApiKey,
                geminiModel = geminiModel,
                isGeminiEnabled = isGeminiEnabled,
                openAiApiKey = openAiApiKey,
                openAiModel = openAiModel,
                isOpenAiEnabled = isOpenAiEnabled,
                isFallbackEnabled = isFallbackEnabled,
                systemPrompt = systemPrompt,
                isScreenScanEnabled = isScreenScanEnabled,
                isAreaScanEnabled = isAreaScanEnabled,
                maxImageResolution = maxImageResolution,
                adminPin = adminPin,
                appTheme = appTheme,
                preferredLanguage = preferredLanguage,
                bubbleStyle = bubbleStyle,
                bubbleCustomImagePath = bubbleCustomImagePath,
                bubblePresetIcon = bubblePresetIcon,
                bubbleText = bubbleText,
                bubbleGradient = bubbleGradient,
                bubbleSize = bubbleSize,
                bubbleAlpha = bubbleAlpha
            )
        }
    }

    fun setCustomBubbleImage(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val outputFile = java.io.File(context.filesDir, "custom_floating_icon_${System.currentTimeMillis()}.png")
                    // Delete older files if any
                    context.filesDir.listFiles { file -> file.name.startsWith("custom_floating_icon_") }?.forEach { it.delete() }
                    
                    val outputStream = java.io.FileOutputStream(outputFile)
                    inputStream.copyTo(outputStream)
                    inputStream.close()
                    outputStream.flush()
                    outputStream.close()

                    adminPrefs.updateSettings(
                        bubbleCustomImagePath = outputFile.absolutePath,
                        bubbleStyle = if (adminSettings.value.bubbleStyle == "icon_only") "circle" else adminSettings.value.bubbleStyle
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Custom Floating Icon updated successfully!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to save custom icon: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun removeCustomBubbleImage(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.filesDir.listFiles { file -> file.name.startsWith("custom_floating_icon_") }?.forEach { it.delete() }
                adminPrefs.updateSettings(bubbleCustomImagePath = "")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Reset to default AI icon", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {}
        }
    }

    fun testGeminiConnection(apiKey: String, model: String) {
        viewModelScope.launch {
            _testApiResult.value = Pair("Testing Gemini connection...", false)
            val result = aiRepository.testGemini(apiKey, model)
            _testApiResult.value = Pair(result.second, result.first)
        }
    }

    fun testOpenAiConnection(apiKey: String, model: String) {
        viewModelScope.launch {
            _testApiResult.value = Pair("Testing OpenAI connection...", false)
            val result = aiRepository.testOpenAi(apiKey, model)
            _testApiResult.value = Pair(result.second, result.first)
        }
    }

    fun resetStats() {
        viewModelScope.launch {
            adminPrefs.resetStats()
        }
    }
}
