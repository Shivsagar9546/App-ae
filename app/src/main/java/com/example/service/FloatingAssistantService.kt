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
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
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
import com.example.data.preferences.AdminSettings
import com.example.data.preferences.AdminPreferencesRepository
import com.example.ui.screens.FloatingBubbleView
import com.example.ui.screens.FloatingOcrTextGrabberView
import com.example.ui.screens.FloatingPopUpView
import com.example.ui.screens.FloatingQuickSolutionHudView
import com.example.ui.screens.SelectedAreaCropOverlay
import com.example.ui.theme.OmniAITheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class FloatingAssistantService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, TextToSpeech.OnInitListener {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val appViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = appViewModelStore

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var aiRepository: AiRepository
    private lateinit var voiceHelper: VoiceRecognitionHelper
    private var textToSpeech: TextToSpeech? = null

    // Overlay Views
    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var popupView: ComposeView? = null
    private var popupParams: WindowManager.LayoutParams? = null

    private var cropOverlayView: ComposeView? = null
    private var cropOverlayParams: WindowManager.LayoutParams? = null
    private var cropBackgroundBitmap: Bitmap? = null

    private var ocrView: ComposeView? = null
    private var ocrParams: WindowManager.LayoutParams? = null

    private var hudView: ComposeView? = null
    private var hudParams: WindowManager.LayoutParams? = null

    private var accessibilityPromptView: ComposeView? = null
    private var accessibilityPromptParams: WindowManager.LayoutParams? = null

    // State flows for popup UI
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String>(UUID.randomUUID().toString())
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _attachedImage = MutableStateFlow<Bitmap?>(null)

    // State flows for OCR Text Grabber (Feature 1)
    private val _ocrExtractedText = MutableStateFlow<String?>(null)
    val ocrExtractedText: StateFlow<String?> = _ocrExtractedText.asStateFlow()

    private val _isOcrLoading = MutableStateFlow(false)
    val isOcrLoading: StateFlow<Boolean> = _isOcrLoading.asStateFlow()

    // State flows for Quick Solution HUD (Feature 2)
    private val _hudSolutionText = MutableStateFlow<String?>(null)
    val hudSolutionText: StateFlow<String?> = _hudSolutionText.asStateFlow()

    private val _isHudLoading = MutableStateFlow(false)
    val isHudLoading: StateFlow<Boolean> = _isHudLoading.asStateFlow()

    private val _hudTitle = MutableStateFlow("Quick AI Solution")
    val hudTitle: StateFlow<String> = _hudTitle.asStateFlow()

    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onCreate() {
        super.onCreate()
        try {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {}
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val app = application as OmniAIApplication
        aiRepository = AiRepository(app.adminPreferences)
        voiceHelper = VoiceRecognitionHelper(this)

        try {
            textToSpeech = TextToSpeech(this, this)
        } catch (e: Exception) {}

        updateScreenDimensions()
        activeServiceInstance = this

        // Under Android 15, we must show the visible overlay window BEFORE starting foreground service to satisfy SYSTEM_ALERT_WINDOW background start rules.
        showBubble()
        startForegroundServiceNotification()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                textToSpeech?.language = Locale.getDefault()
            } catch (e: Exception) {}
        }
    }

    private fun speakText(text: String) {
        try {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "HudUtterance")
        } catch (e: Exception) {
            Toast.makeText(this, "Speech not ready", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateScreenDimensions() {
        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
            screenHeight = if (metrics.heightPixels > 0) metrics.heightPixels else 2400
        } catch (e: Exception) {
            screenWidth = 1080
            screenHeight = 2400
        }
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

        val notification = createForegroundNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {}
        }
    }

    private fun createForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingAssistantService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cropPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingAssistantService::class.java).apply { action = ACTION_CROP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val instantPendingIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, FloatingAssistantService::class.java).apply { action = ACTION_INSTANT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chatPendingIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, FloatingAssistantService::class.java).apply { action = ACTION_CHAT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OmniAIApplication.CHANNEL_FLOATING_SERVICE)
            .setContentTitle("OmniAI Assistant Panel")
            .setContentText("Direct access to active vision tools")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setOngoing(true)
            .addAction(0, "✂️ Crop", cropPendingIntent)
            .addAction(0, "⚡ Text", instantPendingIntent)
            .addAction(0, "💬 Chat", chatPendingIntent)
            .addAction(0, "❌ Exit", stopPendingIntent)
            .build()
    }

    fun promoteToMediaProjectionFgs() {
        try {
            val notification = createForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("FloatingService", "Could not promote to mediaProjection FGS", e)
        }
    }

    fun demoteFromMediaProjectionFgs() {
        try {
            val notification = createForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.w("FloatingService", "Could not demote from mediaProjection FGS", e)
        }
    }

    private fun safelyRemoveView(viewToRemove: View?) {
        if (viewToRemove == null) return
        try {
            viewToRemove.visibility = View.GONE
        } catch (e: Exception) {}
        viewToRemove.post {
            try {
                if (viewToRemove.isAttachedToWindow) {
                    windowManager.removeView(viewToRemove)
                }
            } catch (e: Exception) {
                try {
                    windowManager.removeViewImmediate(viewToRemove)
                } catch (e2: Exception) {}
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CROP -> {
                startAreaCropFlow()
            }
            ACTION_INSTANT -> {
                startOcrTextExtraction(null)
            }
            ACTION_CHAT -> {
                showPopup()
            }
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
        hideOcrGrabber()
        hideQuickHud()

        if (bubbleView != null) {
            try {
                bubbleView?.visibility = View.VISIBLE
                windowManager.updateViewLayout(bubbleView, bubbleParams)
                return
            } catch (e: Exception) {
                try {
                    windowManager.removeView(bubbleView)
                } catch (ex: Exception) {}
                bubbleView = null
            }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - 220).coerceAtLeast(20)
            y = screenHeight / 3
        }

        bubbleView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setViewTreeViewModelStoreOwner(this@FloatingAssistantService)
            setContent {
                val omniApp = application as OmniAIApplication
                val adminSettings by omniApp.adminPreferences.settingsFlow.collectAsState(initial = AdminSettings())

                OmniAITheme {
                    FloatingBubbleView(
                        bubbleStyle = adminSettings.bubbleStyle,
                        customImagePath = adminSettings.bubbleCustomImagePath,
                        presetIcon = adminSettings.bubblePresetIcon,
                        customText = adminSettings.bubbleText,
                        gradientPreset = adminSettings.bubbleGradient,
                        bubbleSize = adminSettings.bubbleSize,
                        bubbleAlpha = adminSettings.bubbleAlpha,
                        onUpdateAlpha = { newAlpha ->
                            serviceScope.launch {
                                omniApp.adminPreferences.updateSettings(bubbleAlpha = newAlpha)
                            }
                        },
                        onDrag = { dx, dy ->
                            bubbleParams?.let { params ->
                                updateScreenDimensions()
                                val bubbleW = bubbleView?.width?.takeIf { it > 0 } ?: 160
                                val bubbleH = bubbleView?.height?.takeIf { it > 0 } ?: 160
                                val maxX = (screenWidth - bubbleW).coerceAtLeast(0)
                                val maxY = (screenHeight - bubbleH).coerceAtLeast(0)

                                params.x = (params.x + dx.toInt()).coerceIn(0, maxX)
                                params.y = (params.y + dy.toInt()).coerceIn(0, maxY)
                                try {
                                    windowManager.updateViewLayout(bubbleView, params)
                                } catch (e: Exception) {}
                            }
                        },
                        onDragEnd = {
                            bubbleParams?.let { params ->
                                updateScreenDimensions()
                                val bubbleW = bubbleView?.width?.takeIf { it > 0 } ?: 160
                                val bubbleH = bubbleView?.height?.takeIf { it > 0 } ?: 160
                                val maxX = (screenWidth - bubbleW).coerceAtLeast(0)
                                val maxY = (screenHeight - bubbleH).coerceAtLeast(0)

                                params.x = params.x.coerceIn(0, maxX)
                                params.y = params.y.coerceIn(0, maxY)
                                try {
                                    windowManager.updateViewLayout(bubbleView, params)
                                } catch (e: Exception) {}
                            }
                        },
                        onBubbleClick = {
                            showPopup()
                        },
                        onInstantTextScan = {
                            showRemovedFeatureToast()
                        },
                        onScreenshotCapture = {
                            captureScreenshotAndAttach()
                        },
                        onScanScreen = {
                            showRemovedFeatureToast()
                        },
                        onAreaScan = {
                            startAreaCropFlow()
                        },
                        onOcrGrabber = {
                            showRemovedFeatureToast()
                        },
                        onQuickHud = {
                            showRemovedFeatureToast()
                        },
                        onVoiceClick = {
                            startVoiceQuery()
                        },
                        onOpenSettings = {
                            val intent = Intent(this@FloatingAssistantService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("NAV_TARGET", "assistant_hub")
                            }
                            startActivity(intent)
                        },
                        onClose = {
                            stopSelf()
                        }
                    )
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
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable overlay permission in settings", Toast.LENGTH_SHORT).show()
            return
        }
        hideBubble()
        hideOcrGrabber()
        hideQuickHud()

        if (popupView != null) {
            try {
                popupView?.visibility = View.VISIBLE
                return
            } catch (e: Exception) {
                hidePopup()
            }
        }

        updateScreenDimensions()
        val defaultWidth = (screenWidth * 0.92f).toInt().coerceIn(340, 1020)
        val defaultHeight = (screenHeight * 0.65f).toInt().coerceIn(460, 1600)

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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        popupView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setViewTreeViewModelStoreOwner(this@FloatingAssistantService)
            setContent {
                val msgs by _messages.collectAsState()
                val isGen by _isGenerating.collectAsState()
                val status by _statusText.collectAsState()
                val attachedImg by _attachedImage.collectAsState()

                OmniAITheme {
                    FloatingPopUpView(
                        messages = msgs,
                        isGenerating = isGen,
                        statusText = status,
                        onSendMessage = { text, img ->
                            sendMessage(text, img, isScan = false)
                        },
                        onInstantTextScan = {
                            Toast.makeText(this@FloatingAssistantService, "Instant Text Scan: Feature setup in progress", Toast.LENGTH_SHORT).show()
                        },
                        onScanScreen = {
                            Toast.makeText(this@FloatingAssistantService, "Screen Scan: Feature setup in progress", Toast.LENGTH_SHORT).show()
                        },
                        onAreaScan = {
                            startAreaCropFlow()
                        },
                        onScreenshotCapture = {
                            captureScreenshotAndAttach()
                        },
                        externalAttachedBitmap = attachedImg,
                        onClearExternalAttachedBitmap = {
                            _attachedImage.value = null
                        },
                        onOcrGrabber = {
                            Toast.makeText(this@FloatingAssistantService, "OCR Grabber: Feature setup in progress", Toast.LENGTH_SHORT).show()
                        },
                        onQuickHud = {
                            Toast.makeText(this@FloatingAssistantService, "Quick HUD: Feature setup in progress", Toast.LENGTH_SHORT).show()
                        },
                        onPickGalleryImage = { callback ->
                            FloatingImagePickerActivity.launchGalleryPicker(this@FloatingAssistantService) { bitmap ->
                                callback(bitmap)
                            }
                        },
                        onTakePhoto = { callback ->
                            FloatingImagePickerActivity.launchCameraPicker(this@FloatingAssistantService) { bitmap ->
                                callback(bitmap)
                            }
                        },
                        onVoiceInput = {
                            startVoiceQuery()
                        },
                        onSpeakText = { text ->
                            if (text.isBlank()) {
                                textToSpeech?.stop()
                            } else {
                                speakText(text)
                            }
                        },
                        onClearMessages = {
                            clearChat()
                        },
                        onRegenerate = {
                            regenerateLastResponse()
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
                                val newW = (params.width + dw.toInt()).coerceIn(320, screenWidth - 20)
                                val newH = (params.height + dh.toInt()).coerceIn(400, screenHeight - 60)
                                params.width = newW
                                params.height = newH
                                try {
                                    windowManager.updateViewLayout(popupView, popupParams)
                                } catch (e: Exception) {}
                            }
                        },
                        onAlphaChanged = { newAlpha ->
                            popupParams?.let { params ->
                                params.alpha = newAlpha.coerceIn(0.25f, 1.0f)
                                try {
                                    windowManager.updateViewLayout(popupView, params)
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
        val view = popupView
        popupView = null
        safelyRemoveView(view)
    }

    // ==========================================
    // FEATURE 1: OCR TEXT GRABBER OVERLAY
    // ==========================================

    fun showOcrGrabber() {
        if (!Settings.canDrawOverlays(this)) return
        hideBubble()
        hidePopup()
        hideQuickHud()

        if (ocrView != null) {
            try {
                ocrView?.visibility = View.VISIBLE
                return
            } catch (e: Exception) {
                hideOcrGrabber()
            }
        }

        updateScreenDimensions()
        val defaultWidth = (screenWidth * 0.90f).toInt().coerceIn(320, 980)
        val defaultHeight = (screenHeight * 0.55f).toInt().coerceIn(400, 1300)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        ocrParams = WindowManager.LayoutParams(
            defaultWidth,
            defaultHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        ocrView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setViewTreeViewModelStoreOwner(this@FloatingAssistantService)
            setContent {
                val ocrText by _ocrExtractedText.collectAsState()
                val isLoading by _isOcrLoading.collectAsState()

                OmniAITheme {
                    FloatingOcrTextGrabberView(
                        extractedText = ocrText,
                        isLoading = isLoading,
                        onCopyAll = {
                            Toast.makeText(this@FloatingAssistantService, "Copied all extracted text!", Toast.LENGTH_SHORT).show()
                        },
                        onTranslate = { text ->
                            hideOcrGrabber()
                            showPopup()
                            sendMessage("Translate this extracted text to Hindi and simple English:\n\n\"$text\"", null, isScan = false)
                        },
                        onAskAi = { text ->
                            hideOcrGrabber()
                            showPopup()
                            sendMessage("Explain / solve this text from my screen:\n\n\"$text\"", null, isScan = false)
                        },
                        onClose = {
                            hideOcrGrabber()
                            showBubble()
                        },
                        onDragHeader = { dx, dy ->
                            ocrParams?.let { params ->
                                params.x += dx.toInt()
                                params.y += dy.toInt()
                                try {
                                    windowManager.updateViewLayout(ocrView, ocrParams)
                                } catch (e: Exception) {}
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(ocrView, ocrParams)
        } catch (e: Exception) {}
    }

    fun hideOcrGrabber() {
        val view = ocrView
        ocrView = null
        safelyRemoveView(view)
    }

    private fun showRemovedFeatureToast() {
        Toast.makeText(
            this,
            "Play Protect safety ke liye screen crop features ko hata diya gaya hai. Kripya normal screenshot lekar chat me directly upload karein!",
            Toast.LENGTH_LONG
        ).show()
    }

    fun startOcrTextExtraction(cropRect: Rect?) {
        hidePopup()
        hideBubble()
        hideQuickHud()

        _isOcrLoading.value = true
        _ocrExtractedText.value = "Scanning screen and extracting text..."
        showOcrGrabber()

        serviceScope.launch {
            kotlinx.coroutines.delay(200)
            captureScreenshotHelper(
                onSuccess = { fullBitmap ->
                    val targetBmp = if (cropRect != null) {
                        val cropped = cropBitmap(fullBitmap, cropRect)
                        fullBitmap.recycle()
                        cropped
                    } else {
                        fullBitmap
                    }
                    
                    serviceScope.launch(Dispatchers.IO) {
                        val messages = listOf(
                            AiMessage(
                                role = "user",
                                text = "Analyze this image and perform high-accuracy OCR. Extract and return ALL readable text from this image exactly as it appears. Keep it raw, without any conversational filler, explanations, or labels."
                            )
                        )
                        val result = aiRepository.askAi(
                            messages = messages,
                            imageBitmap = targetBmp,
                            isScreenScan = true
                        )
                        targetBmp.recycle()
                        
                        withContext(Dispatchers.Main) {
                            _isOcrLoading.value = false
                            when (result) {
                                is AiResult.Success -> {
                                    _ocrExtractedText.value = result.text
                                }
                                is AiResult.Error -> {
                                    _ocrExtractedText.value = "⚠️ Error: ${result.message}"
                                }
                            }
                        }
                    }
                },
                onGalleryBackup = {
                    FloatingImagePickerActivity.launchScreenCapture(this@FloatingAssistantService) { fullBitmap ->
                        val targetBmp = if (cropRect != null) {
                            val cropped = cropBitmap(fullBitmap, cropRect)
                            fullBitmap.recycle()
                            cropped
                        } else {
                            fullBitmap
                        }
                        
                        serviceScope.launch(Dispatchers.IO) {
                            val messages = listOf(
                                AiMessage(
                                    role = "user",
                                    text = "Analyze this image and perform high-accuracy OCR. Extract and return ALL readable text from this image exactly as it appears. Keep it raw, without any conversational filler, explanations, or labels."
                                )
                            )
                            val result = aiRepository.askAi(
                                messages = messages,
                                imageBitmap = targetBmp,
                                isScreenScan = true
                            )
                            targetBmp.recycle()
                            
                            withContext(Dispatchers.Main) {
                                _isOcrLoading.value = false
                                when (result) {
                                    is AiResult.Success -> {
                                        _ocrExtractedText.value = result.text
                                    }
                                    is AiResult.Error -> {
                                        _ocrExtractedText.value = "⚠️ Error: ${result.message}"
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    // ==========================================
    // FEATURE 2: AUTO-FLOATING QUICK SOLUTION HUD
    // ==========================================

    fun showQuickHud() {
        if (!Settings.canDrawOverlays(this)) return
        hideBubble()
        hidePopup()
        hideOcrGrabber()

        if (hudView != null) {
            try {
                hudView?.visibility = View.VISIBLE
                return
            } catch (e: Exception) {
                hideQuickHud()
            }
        }

        updateScreenDimensions()
        val defaultWidth = (screenWidth * 0.94f).toInt().coerceIn(320, 1020)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        hudParams = WindowManager.LayoutParams(
            defaultWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 120
        }

        hudView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setViewTreeViewModelStoreOwner(this@FloatingAssistantService)
            setContent {
                val title by _hudTitle.collectAsState()
                val solText by _hudSolutionText.collectAsState()
                val isLoading by _isHudLoading.collectAsState()

                OmniAITheme {
                    FloatingQuickSolutionHudView(
                        title = title,
                        solutionText = solText,
                        isLoading = isLoading,
                        onSpeak = { text ->
                            speakText(text)
                        },
                        onOpenFullChat = {
                            hideQuickHud()
                            showPopup()
                        },
                        onClose = {
                            hideQuickHud()
                            showBubble()
                        },
                        onDragHeader = { dx, dy ->
                            hudParams?.let { params ->
                                params.x += dx.toInt()
                                params.y += dy.toInt()
                                try {
                                    windowManager.updateViewLayout(hudView, hudParams)
                                } catch (e: Exception) {}
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(hudView, hudParams)
        } catch (e: Exception) {}
    }

    fun hideQuickHud() {
        val view = hudView
        hudView = null
        safelyRemoveView(view)
    }

    fun startQuickHudSolve(cropRect: Rect?) {
        hidePopup()
        hideBubble()
        hideOcrGrabber()

        _isHudLoading.value = true
        _hudSolutionText.value = "Analyzing screen and generating solution..."
        showQuickHud()

        serviceScope.launch {
            kotlinx.coroutines.delay(200)
            captureScreenshotHelper(
                onSuccess = { fullBitmap ->
                    val targetBmp = if (cropRect != null) {
                        val cropped = cropBitmap(fullBitmap, cropRect)
                        fullBitmap.recycle()
                        cropped
                    } else {
                        fullBitmap
                    }
                    
                    serviceScope.launch(Dispatchers.IO) {
                        val messages = listOf(
                            AiMessage(
                                role = "user",
                                text = "Identify the main question, problem, code, or image on the screen. Solve it step-by-step with clear, concise, and direct answers first, followed by clear explanations. Use markdown if formatting is helpful. Make it extremely brief to fit a small floating widget."
                            )
                        )
                        val result = aiRepository.askAi(
                            messages = messages,
                            imageBitmap = targetBmp,
                            isScreenScan = true
                        )
                        targetBmp.recycle()
                        
                        withContext(Dispatchers.Main) {
                            _isHudLoading.value = false
                            when (result) {
                                is AiResult.Success -> {
                                    _hudSolutionText.value = result.text
                                }
                                is AiResult.Error -> {
                                    _hudSolutionText.value = "⚠️ Error: ${result.message}"
                                }
                            }
                        }
                    }
                },
                onGalleryBackup = {
                    FloatingImagePickerActivity.launchScreenCapture(this@FloatingAssistantService) { fullBitmap ->
                        val targetBmp = if (cropRect != null) {
                            val cropped = cropBitmap(fullBitmap, cropRect)
                            fullBitmap.recycle()
                            cropped
                        } else {
                            fullBitmap
                        }
                        
                        serviceScope.launch(Dispatchers.IO) {
                            val messages = listOf(
                                AiMessage(
                                    role = "user",
                                    text = "Identify the main question, problem, code, or image on the screen. Solve it step-by-step with clear, concise, and direct answers first, followed by clear explanations. Use markdown if formatting is helpful. Make it extremely brief to fit a small floating widget."
                                )
                            )
                            val result = aiRepository.askAi(
                                messages = messages,
                                imageBitmap = targetBmp,
                                isScreenScan = true
                            )
                            targetBmp.recycle()
                            
                            withContext(Dispatchers.Main) {
                                _isHudLoading.value = false
                                when (result) {
                                    is AiResult.Success -> {
                                        _hudSolutionText.value = result.text
                                    }
                                    is AiResult.Error -> {
                                        _hudSolutionText.value = "⚠️ Error: ${result.message}"
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    // ==========================================
    // AREA CROP SELECTOR OVERLAY
    // ==========================================

    fun captureScreenshotAndAttach() {
        hidePopup()
        hideBubble()
        hideOcrGrabber()
        hideQuickHud()

        serviceScope.launch {
            kotlinx.coroutines.delay(200)
            captureScreenshotHelper(
                onSuccess = { bitmap ->
                    _attachedImage.value = bitmap
                    showPopup()
                },
                onGalleryBackup = {
                    FloatingImagePickerActivity.launchScreenCapture(this@FloatingAssistantService) { bitmap ->
                        _attachedImage.value = bitmap
                        showPopup()
                    }
                }
            )
        }
    }

    fun startAreaCropFlow() {
        hidePopup()
        hideBubble()
        hideOcrGrabber()
        hideQuickHud()

        serviceScope.launch {
            kotlinx.coroutines.delay(200)
            captureScreenshotHelper(
                onSuccess = { bitmap ->
                    showCropOverlay(bitmap)
                },
                onGalleryBackup = {
                    FloatingImagePickerActivity.launchScreenCapture(this@FloatingAssistantService) { bitmap ->
                        showCropOverlay(bitmap)
                    }
                }
            )
        }
    }

    private fun cropBitmap(original: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, original.width - 1)
        val top = rect.top.coerceIn(0, original.height - 1)
        val right = rect.right.coerceIn(left + 1, original.width)
        val bottom = rect.bottom.coerceIn(top + 1, original.height)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(original, left, top, width, height)
    }

    fun showCropOverlay(bitmap: Bitmap) {
        cropBackgroundBitmap?.recycle()
        cropBackgroundBitmap = bitmap
        hidePopup()
        hideBubble()
        hideOcrGrabber()
        hideQuickHud()

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        cropOverlayView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setViewTreeViewModelStoreOwner(this@FloatingAssistantService)
            setContent {
                OmniAITheme {
                    SelectedAreaCropOverlay(
                        backgroundImage = cropBackgroundBitmap,
                        onAreaSelected = { rect ->
                            hideCropOverlay()
                            if (rect != null) {
                                val bg = cropBackgroundBitmap
                                if (bg != null) {
                                    try {
                                        val cropped = cropBitmap(bg, rect)
                                        _attachedImage.value = cropped
                                        bg.recycle()
                                        cropBackgroundBitmap = null
                                        showPopup()
                                    } catch (e: Exception) {
                                        Toast.makeText(this@FloatingAssistantService, "Cropping failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                        showPopup()
                                    }
                                } else {
                                    showPopup()
                                }
                            } else {
                                showPopup()
                            }
                        },
                        onCancel = {
                            hideCropOverlay()
                            cropBackgroundBitmap?.recycle()
                            cropBackgroundBitmap = null
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
        val view = cropOverlayView
        cropOverlayView = null
        safelyRemoveView(view)
    }

    // ==========================================
    // SCREEN SCAN & AI LOGIC
    // ==========================================

    /**
     * Reads screen text directly using fast on-device screen capture and OCR text extraction.
     */
    fun startInstantTextScan() {
        startOcrTextExtraction(null)
    }

    fun startScreenScan(cropRect: Rect?) {
        hidePopup()
        hideBubble()
        hideOcrGrabber()
        hideQuickHud()

        serviceScope.launch {
            kotlinx.coroutines.delay(200)
            captureScreenshotHelper(
                onSuccess = { fullBitmap ->
                    val targetBmp = if (cropRect != null) {
                        val cropped = cropBitmap(fullBitmap, cropRect)
                        fullBitmap.recycle()
                        cropped
                    } else {
                        fullBitmap
                    }
                    _attachedImage.value = targetBmp
                    showPopup()
                    sendMessage("Analyze this captured area of my screen and explain it in detail.", targetBmp, isScan = true)
                },
                onGalleryBackup = {
                    FloatingImagePickerActivity.launchScreenCapture(this@FloatingAssistantService) { fullBitmap ->
                        val targetBmp = if (cropRect != null) {
                            val cropped = cropBitmap(fullBitmap, cropRect)
                            fullBitmap.recycle()
                            cropped
                        } else {
                            fullBitmap
                        }
                        _attachedImage.value = targetBmp
                        showPopup()
                        sendMessage("Analyze this captured area of my screen and explain it in detail.", targetBmp, isScan = true)
                    }
                }
            )
        }
    }

    private fun sendScreenAnalysisRequest(bitmap: Bitmap, prompt: String) {
        _isGenerating.value = true
        _statusText.value = "Analyzing screen..."

        serviceScope.launch {
            val app = application as OmniAIApplication
            val convId = _currentConversationId.value

            val imageBase64 = withContext(Dispatchers.IO) {
                try {
                    val maxDim = 800
                    var scaled = bitmap
                    if (bitmap.width > maxDim || bitmap.height > maxDim) {
                        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                        val newW = if (bitmap.width > bitmap.height) maxDim else (maxDim * ratio).toInt()
                        val newH = if (bitmap.width > bitmap.height) (maxDim / ratio).toInt() else maxDim
                        scaled = Bitmap.createScaledBitmap(bitmap, newW.coerceAtLeast(1), newH.coerceAtLeast(1), true)
                    }
                    val cacheFile = java.io.File(app.cacheDir, "img_${java.util.UUID.randomUUID()}.jpg")
                    java.io.FileOutputStream(cacheFile).use { fos ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 75, fos)
                    }
                    if (scaled != bitmap) {
                        scaled.recycle()
                    }
                    cacheFile.absolutePath
                } catch (e: Exception) {
                    null
                }
            }

            val userMsg = ChatMessage(
                conversationId = convId,
                role = "user",
                text = prompt,
                imageBase64 = imageBase64,
                isScreenScan = true,
                timestamp = System.currentTimeMillis()
            )

            _messages.value = _messages.value + userMsg

            withContext(Dispatchers.IO) {
                try {
                    app.database.chatDao().insertConversation(
                        Conversation(
                            id = convId,
                            title = "Screen Scan: ${prompt.take(24)}...",
                            updatedAt = System.currentTimeMillis(),
                            lastMessagePreview = prompt
                        )
                    )
                    app.database.chatDao().insertMessage(userMsg)
                } catch (e: Exception) {}
            }

            val aiMessages = _messages.value.map {
                AiMessage(role = it.role, text = it.text)
            }

            val result = withContext(Dispatchers.IO) {
                aiRepository.askAi(
                    messages = aiMessages,
                    imageBitmap = bitmap,
                    isScreenScan = true
                )
            }
            bitmap.recycle()

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
                    withContext(Dispatchers.IO) {
                        try {
                            app.database.chatDao().insertMessage(modelMsg)
                        } catch (e: Exception) {}
                    }
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
                    withContext(Dispatchers.IO) {
                        try {
                            app.database.chatDao().insertMessage(errorMsg)
                        } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    fun sendMessage(text: String, imageBitmap: Bitmap? = null, isScan: Boolean = false) {
        if (text.isBlank() && imageBitmap == null) return

        val promptText = if (text.isBlank() && imageBitmap != null) {
            "Explain this photo and solve or describe what is shown here."
        } else {
            text
        }

        _isGenerating.value = true

        serviceScope.launch {
            val app = application as OmniAIApplication
            val convId = _currentConversationId.value

            val imageBase64 = withContext(Dispatchers.IO) {
                imageBitmap?.let { bitmap ->
                    try {
                        val maxDim = 800
                        var scaled = bitmap
                        if (bitmap.width > maxDim || bitmap.height > maxDim) {
                            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val newW = if (bitmap.width > bitmap.height) maxDim else (maxDim * ratio).toInt()
                            val newH = if (bitmap.width > bitmap.height) (maxDim / ratio).toInt() else maxDim
                            scaled = Bitmap.createScaledBitmap(bitmap, newW.coerceAtLeast(1), newH.coerceAtLeast(1), true)
                        }
                        val cacheFile = java.io.File(app.cacheDir, "img_${java.util.UUID.randomUUID()}.jpg")
                        java.io.FileOutputStream(cacheFile).use { fos ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 75, fos)
                        }
                        if (scaled != bitmap) {
                            scaled.recycle()
                        }
                        cacheFile.absolutePath
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            val userMsg = ChatMessage(
                conversationId = convId,
                role = "user",
                text = promptText,
                imageBase64 = imageBase64,
                isScreenScan = isScan,
                timestamp = System.currentTimeMillis()
            )

            _messages.value = _messages.value + userMsg

            withContext(Dispatchers.IO) {
                try {
                    app.database.chatDao().insertConversation(
                        Conversation(
                            id = convId,
                            title = promptText.take(30),
                            updatedAt = System.currentTimeMillis(),
                            lastMessagePreview = promptText
                        )
                    )
                    app.database.chatDao().insertMessage(userMsg)
                } catch (e: Exception) {}
            }

            val aiMessages = _messages.value.map {
                AiMessage(role = it.role, text = it.text)
            }

            val result = withContext(Dispatchers.IO) {
                aiRepository.askAi(
                    messages = aiMessages,
                    imageBitmap = imageBitmap,
                    isScreenScan = isScan
                )
            }
            imageBitmap?.recycle()

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
                    withContext(Dispatchers.IO) {
                        try {
                            app.database.chatDao().insertMessage(modelMsg)
                        } catch (e: Exception) {}
                    }
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
                    withContext(Dispatchers.IO) {
                        try {
                            app.database.chatDao().insertMessage(errorMsg)
                        } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
        _currentConversationId.value = java.util.UUID.randomUUID().toString()
    }

    fun regenerateLastResponse() {
        val currentMsgs = _messages.value
        if (currentMsgs.isEmpty() || _isGenerating.value) return
        val lastUserMsgIndex = currentMsgs.indexOfLast { it.role.equals("user", ignoreCase = true) }
        if (lastUserMsgIndex != -1) {
            val userMsg = currentMsgs[lastUserMsgIndex]
            val filtered = currentMsgs.take(lastUserMsgIndex + 1)
            _messages.value = filtered
            _isGenerating.value = true

            serviceScope.launch {
                val app = application as OmniAIApplication
                val convId = _currentConversationId.value
                val aiMessages = filtered.map { AiMessage(role = it.role, text = it.text) }
                val result = withContext(Dispatchers.IO) {
                    aiRepository.askAi(messages = aiMessages, isScreenScan = userMsg.isScreenScan)
                }
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
                        withContext(Dispatchers.IO) {
                            try {
                                app.database.chatDao().insertMessage(modelMsg)
                            } catch (e: Exception) {}
                        }
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
                    }
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

    fun showAccessibilityPromptDialog(onGalleryBackup: () -> Unit) {
        if (!Settings.canDrawOverlays(this)) return
        hideAccessibilityPromptDialog()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        accessibilityPromptParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        accessibilityPromptView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@FloatingAssistantService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAssistantService)
            setViewTreeViewModelStoreOwner(this@FloatingAssistantService)
            setContent {
                OmniAITheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .padding(16.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(24.dp)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Instant Screen Crop Enable Karein",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Google ke \"Start recording\" warning dialogs ko permanently bypass karne aur instant single-click screen crop/capture ke liye please Settings me jaakar OmniAI ki Accessibility Service ko ON karein. Aap direct standard screen capture bhee use kar sakte hain.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = {
                                        hideAccessibilityPromptDialog()
                                        showBubble()
                                        try {
                                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(this@FloatingAssistantService, "Could not open settings: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Settings me ON karein (No Warning Dialog)")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        hideAccessibilityPromptDialog()
                                        onGalleryBackup()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Normal Screen Capture (Standard)", color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        hideAccessibilityPromptDialog()
                                        showBubble()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Cancel", color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }
        }

        try {
            windowManager.addView(accessibilityPromptView, accessibilityPromptParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hideAccessibilityPromptDialog() {
        accessibilityPromptView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            accessibilityPromptView = null
        }
    }

    private fun captureScreenshotHelper(
        onSuccess: (Bitmap) -> Unit,
        onGalleryBackup: () -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AccessibilityScreenshotService.isEnabled()) {
            val mainExecutor = java.util.concurrent.Executor { command ->
                serviceScope.launch(Dispatchers.Main) {
                    command.run()
                }
            }
            AccessibilityScreenshotService.captureScreenSilently(
                mainExecutor,
                onSuccess = { bitmap ->
                    onSuccess(bitmap)
                },
                onFailure = { error ->
                    Toast.makeText(this, "Failed: $error", Toast.LENGTH_SHORT).show()
                    onGalleryBackup()
                }
            )
        } else {
            showAccessibilityPromptDialog(onGalleryBackup)
        }
    }

    override fun onDestroy() {
        hideAccessibilityPromptDialog()
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        hideCropOverlay()
        hidePopup()
        hideOcrGrabber()
        hideQuickHud()
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView)
            } catch (e: Exception) {}
            bubbleView = null
        }
        try {
            voiceHelper.stopListening()
        } catch (e: Exception) {}
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (e: Exception) {}
        serviceScope.cancel()
        appViewModelStore.clear()
        activeServiceInstance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 101
        const val ACTION_STOP_SERVICE = "action_stop_floating_service"
        const val ACTION_CROP = "com.example.service.ACTION_CROP"
        const val ACTION_INSTANT = "com.example.service.ACTION_INSTANT"
        const val ACTION_CHAT = "com.example.service.ACTION_CHAT"
        var activeServiceInstance: FloatingAssistantService? = null
            private set

        fun isRunning(): Boolean = activeServiceInstance != null
    }
}
