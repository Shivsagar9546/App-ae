package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.OmniAIApplication
import com.example.R
import com.example.data.ai.AiMessage
import com.example.data.ai.AiRepository
import com.example.data.ai.AiResult
import com.example.data.local.ChatMessage
import com.example.data.local.Conversation
import com.example.ui.screens.FloatingBubbleView
import com.example.ui.screens.FloatingPopUpView
import com.example.ui.screens.SelectedAreaCropOverlay
import com.example.ui.theme.OmniAITheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class FloatingAssistantService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var aiRepository: AiRepository
    private lateinit var screenCaptureHelper: ScreenCaptureHelper
    private lateinit var voiceHelper: VoiceRecognitionHelper

    // Overlay Views
    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var popupView: ComposeView? = null
    private var popupParams: WindowManager.LayoutParams? = null

    private var cropOverlayView: ComposeView? = null
    private var cropOverlayParams: WindowManager.LayoutParams? = null

    // State flows for popup UI
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String>(UUID.randomUUID().toString())
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val app = application as OmniAIApplication
        aiRepository = AiRepository(app.adminPreferences)
        screenCaptureHelper = ScreenCaptureHelper(this)
        voiceHelper = VoiceRecognitionHelper(this)

        updateScreenDimensions()
        activeServiceInstance = this

        startForegroundServiceNotification()
        showBubble()
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun startForegroundServiceNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingAssistantService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, OmniAIApplication.CHANNEL_FLOATING_SERVICE)
            .setContentTitle("OmniAI Floating Assistant")
            .setContentText("Tap to open full app or use the floating bubble over other apps")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close Assistant", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // ==========================================
    // FLOATING BUBBLE
    // ==========================================

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        hidePopup()
        hideCropOverlay()

        if (bubbleView != null) {
            bubbleView?.visibility = View.VISIBLE
            return
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 220
            y = screenHeight / 3
        }

        bubbleView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setContent {
                OmniAITheme {
                    FloatingBubbleView(
                        onBubbleClick = {
                            showPopup()
                        },
                        onScanScreen = {
                            startScreenScan(cropRect = null)
                        },
                        onAreaScan = {
                            showCropOverlay()
                        },
                        onVoiceClick = {
                            startVoiceQuery()
                        },
                        onOpenSettings = {
                            val intent = Intent(this@FloatingAssistantService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("NAV_TARGET", "settings")
                            }
                            startActivity(intent)
                        },
                        onClose = {
                            stopSelf()
                        }
                    )
                }
            }

            var initialX = 0
            var initialY = 0
            var touchStartX = 0f
            var touchStartY = 0f
            var isDragging = false

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams?.x ?: 0
                        initialY = bubbleParams?.y ?: 0
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchStartX).toInt()
                        val dy = (event.rawY - touchStartY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            bubbleParams?.x = initialX + dx
                            bubbleParams?.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(bubbleView, bubbleParams)
                            } catch (e: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            showPopup()
                        } else {
                            // Snap to closest edge (left or right)
                            bubbleParams?.let { params ->
                                val midX = screenWidth / 2
                                params.x = if (params.x < midX) 20 else (screenWidth - 200)
                                try {
                                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                                } catch (e: Exception) {}
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to display floating bubble: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun hideBubble() {
        bubbleView?.visibility = View.GONE
    }

    // ==========================================
    // FLOATING POPUP WINDOW (SAMSUNG POP-UP STYLE)
    // ==========================================

    @SuppressLint("ClickableViewAccessibility")
    fun showPopup() {
        if (!Settings.canDrawOverlays(this)) return
        hideBubble()

        if (popupView != null) {
            popupView?.visibility = View.VISIBLE
            return
        }

        updateScreenDimensions()
        val defaultWidth = (screenWidth * 0.90f).toInt().coerceAtMost(1000)
        val defaultHeight = (screenHeight * 0.65f).toInt().coerceAtMost(1600)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        popupParams = WindowManager.LayoutParams(
            defaultWidth,
            defaultHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        popupView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setContent {
                OmniAITheme {
                    FloatingPopUpView(
                        messages = _messages.value,
                        isGenerating = _isGenerating.value,
                        statusText = _statusText.value,
                        onSendMessage = { text, img ->
                            sendMessage(text, img, isScan = false)
                        },
                        onScanScreen = {
                            startScreenScan(cropRect = null)
                        },
                        onAreaScan = {
                            showCropOverlay()
                        },
                        onVoiceInput = {
                            startVoiceQuery()
                        },
                        onMinimize = {
                            minimizePopup()
                        },
                        onMaximize = {
                            maximizeToFullScreen()
                        },
                        onClose = {
                            hidePopup()
                            showBubble()
                        },
                        onDragHeader = { dx, dy ->
                            popupParams?.let { params ->
                                params.x += dx.toInt()
                                params.y += dy.toInt()
                                try {
                                    windowManager.updateViewLayout(popupView, popupParams)
                                } catch (e: Exception) {}
                            }
                        },
                        onResize = { dw, dh ->
                            popupParams?.let { params ->
                                val newW = (params.width + dw.toInt()).coerceIn(400, screenWidth - 40)
                                val newH = (params.height + dh.toInt()).coerceIn(500, screenHeight - 100)
                                params.width = newW
                                params.height = newH
                                try {
                                    windowManager.updateViewLayout(popupView, popupParams)
                                } catch (e: Exception) {}
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(popupView, popupParams)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to display popup: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun minimizePopup() {
        hidePopup()
        showBubble()
    }

    fun maximizeToFullScreen() {
        hidePopup()
        hideBubble()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("CONVERSATION_ID", _currentConversationId.value)
        }
        startActivity(intent)
    }

    fun hidePopup() {
        if (popupView != null) {
            try {
                windowManager.removeView(popupView)
            } catch (e: Exception) {}
            popupView = null
        }
    }

    // ==========================================
    // AREA CROP SELECTOR OVERLAY
    // ==========================================

    fun showCropOverlay() {
        hidePopup()
        hideBubble()

        if (cropOverlayView != null) {
            cropOverlayView?.visibility = View.VISIBLE
            return
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        cropOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        cropOverlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setContent {
                OmniAITheme {
                    SelectedAreaCropOverlay(
                        onAreaSelected = { rect ->
                            hideCropOverlay()
                            startScreenScan(cropRect = rect)
                        },
                        onCancel = {
                            hideCropOverlay()
                            showPopup()
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(cropOverlayView, cropOverlayParams)
        } catch (e: Exception) {}
    }

    fun hideCropOverlay() {
        if (cropOverlayView != null) {
            try {
                windowManager.removeView(cropOverlayView)
            } catch (e: Exception) {}
            cropOverlayView = null
        }
    }

    // ==========================================
    // SCREEN SCAN & AI LOGIC
    // ==========================================

    fun startScreenScan(cropRect: Rect?) {
        _statusText.value = "Preparing Screen Scan..."
        // Temporarily hide popup & bubble so we don't capture our own overlay
        hidePopup()
        hideBubble()

        serviceScope.launch {
            ScreenCapturePermissionActivity.requestPermission(
                context = this@FloatingAssistantService,
                onGranted = { resultCode, data ->
                    serviceScope.launch {
                        _statusText.value = "Capturing screen..."
                        val bitmap = screenCaptureHelper.captureFrame(resultCode, data, cropRect)
                        showPopup()

                        if (bitmap != null) {
                            _statusText.value = "Analyzing screen with AI..."
                            sendScreenAnalysisRequest(bitmap, prompt = "Scan and solve/explain this screen content accurately.")
                        } else {
                            _statusText.value = null
                            Toast.makeText(this@FloatingAssistantService, "Screen capture failed or timed out", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDenied = {
                    showPopup()
                    _statusText.value = null
                    Toast.makeText(this@FloatingAssistantService, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun sendScreenAnalysisRequest(bitmap: Bitmap, prompt: String) {
        val userMsg = ChatMessage(
            conversationId = _currentConversationId.value,
            role = "user",
            text = prompt,
            isScreenScan = true,
            timestamp = System.currentTimeMillis()
        )

        _messages.value = _messages.value + userMsg
        _isGenerating.value = true

        serviceScope.launch {
            val app = application as OmniAIApplication
            val convId = _currentConversationId.value
            
            // Persist conversation & user message to Room
            app.database.chatDao().insertConversation(
                Conversation(
                    id = convId,
                    title = "Screen Scan: ${prompt.take(24)}...",
                    updatedAt = System.currentTimeMillis(),
                    lastMessagePreview = prompt
                )
            )
            app.database.chatDao().insertMessage(userMsg)

            val aiMessages = _messages.value.map {
                AiMessage(role = it.role, text = it.text)
            }

            val result = aiRepository.askAi(
                messages = aiMessages,
                imageBitmap = bitmap,
                isScreenScan = true
            )

            _isGenerating.value = false
            _statusText.value = null

            when (result) {
                is AiResult.Success -> {
                    val modelMsg = ChatMessage(
                        conversationId = convId,
                        role = "model",
                        text = result.text,
                        timestamp = System.currentTimeMillis()
                    )
                    _messages.value = _messages.value + modelMsg
                    app.database.chatDao().insertMessage(modelMsg)
                }
                is AiResult.Error -> {
                    val errorMsg = ChatMessage(
                        conversationId = convId,
                        role = "model",
                        text = "⚠️ ${result.message}",
                        isError = true,
                        timestamp = System.currentTimeMillis()
                    )
                    _messages.value = _messages.value + errorMsg
                    app.database.chatDao().insertMessage(errorMsg)
                }
            }
        }
    }

    fun sendMessage(text: String, imageBitmap: Bitmap? = null, isScan: Boolean = false) {
        if (text.isBlank() && imageBitmap == null) return

        val userMsg = ChatMessage(
            conversationId = _currentConversationId.value,
            role = "user",
            text = text,
            isScreenScan = isScan,
            timestamp = System.currentTimeMillis()
        )

        _messages.value = _messages.value + userMsg
        _isGenerating.value = true

        serviceScope.launch {
            val app = application as OmniAIApplication
            val convId = _currentConversationId.value

            app.database.chatDao().insertConversation(
                Conversation(
                    id = convId,
                    title = text.take(30),
                    updatedAt = System.currentTimeMillis(),
                    lastMessagePreview = text
                )
            )
            app.database.chatDao().insertMessage(userMsg)

            val aiMessages = _messages.value.map {
                AiMessage(role = it.role, text = it.text)
            }

            val result = aiRepository.askAi(
                messages = aiMessages,
                imageBitmap = imageBitmap,
                isScreenScan = isScan
            )

            _isGenerating.value = false

            when (result) {
                is AiResult.Success -> {
                    val modelMsg = ChatMessage(
                        conversationId = convId,
                        role = "model",
                        text = result.text,
                        timestamp = System.currentTimeMillis()
                    )
                    _messages.value = _messages.value + modelMsg
                    app.database.chatDao().insertMessage(modelMsg)
                }
                is AiResult.Error -> {
                    val errorMsg = ChatMessage(
                        conversationId = convId,
                        role = "model",
                        text = "⚠️ ${result.message}",
                        isError = true,
                        timestamp = System.currentTimeMillis()
                    )
                    _messages.value = _messages.value + errorMsg
                    app.database.chatDao().insertMessage(errorMsg)
                }
            }
        }
    }

    fun startVoiceQuery() {
        voiceHelper.startListening(
            languageCode = "en-US",
            onResult = { recognizedText ->
                if (recognizedText.isNotBlank()) {
                    sendMessage(recognizedText)
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        hideCropOverlay()
        hidePopup()
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView)
            } catch (e: Exception) {}
            bubbleView = null
        }
        activeServiceInstance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 101
        const val ACTION_STOP_SERVICE = "action_stop_floating_service"
        var activeServiceInstance: FloatingAssistantService? = null
            private set

        fun isRunning(): Boolean = activeServiceInstance != null
    }
}
