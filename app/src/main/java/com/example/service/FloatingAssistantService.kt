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
    private lateinit var screenCaptureHelper: ScreenCaptureHelper
    private lateinit var voiceHelper: VoiceRecognitionHelper
    private var textToSpeech: TextToSpeech? = null

    // Overlay Views
    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var popupView: ComposeView? = null
    private var popupParams: WindowManager.LayoutParams? = null

    private var cropOverlayView: ComposeView? = null
    private var cropOverlayParams: WindowManager.LayoutParams? = null

    private var ocrView: ComposeView? = null
    private var ocrParams: WindowManager.LayoutParams? = null

    private var hudView: ComposeView? = null
    private var hudParams: WindowManager.LayoutParams? = null

    // State flows for popup UI
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String>(UUID.randomUUID().toString())
    val currentConversationId: StateFlow<String> = _currentConversationId.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

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
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {}
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val app = application as OmniAIApplication
        aiRepository = AiRepository(app.adminPreferences)
        screenCaptureHelper = ScreenCaptureHelper(this)
        voiceHelper = VoiceRecognitionHelper(this)

        try {
            textToSpeech = TextToSpeech(this, this)
        } catch (e: Exception) {}

        updateScreenDimensions()
        activeServiceInstance = this

        startForegroundServiceNotification()
        showBubble()
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
        val stopIntent = Intent(this, FloatingAssistantService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OmniAIApplication.CHANNEL_FLOATING_SERVICE)
            .setContentTitle("OmniAI Assistant")
            .setContentText("Background assistant active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
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
                            startInstantTextScan()
                        },
                        onScanScreen = {
                            startScreenScan(cropRect = null)
                        },
                        onAreaScan = {
                            showCropOverlay()
                        },
                        onOcrGrabber = {
                            startOcrTextExtraction(cropRect = null)
                        },
                        onQuickHud = {
                            startQuickHudSolve(cropRect = null)
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

                OmniAITheme {
                    FloatingPopUpView(
                        messages = msgs,
                        isGenerating = isGen,
                        statusText = status,
                        onSendMessage = { text, img ->
                            sendMessage(text, img, isScan = false)
                        },
                        onInstantTextScan = {
                            startInstantTextScan()
                        },
                        onScanScreen = {
                            startScreenScan(cropRect = null)
                        },
                        onAreaScan = {
                            showCropOverlay()
                        },
                        onOcrGrabber = {
                            startOcrTextExtraction(cropRect = null)
                        },
                        onQuickHud = {
                            startQuickHudSolve(cropRect = null)
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

    fun startOcrTextExtraction(cropRect: Rect?) {
        hidePopup()
        hideBubble()
        hideOcrGrabber()
        _isOcrLoading.value = true
        _ocrExtractedText.value = null

        serviceScope.launch {
            // Check if zero-dialog Accessibility capture is available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && OmniAccessibilityService.isServiceRunning()) {
                kotlinx.coroutines.delay(100)
                val bitmap = OmniAccessibilityService.captureScreen(cropRect)
                showOcrGrabber()

                if (bitmap != null) {
                    val prompt = "Extract all readable text, questions, options, captions, or paragraphs visible in this image accurately. Return only the extracted text line by line."
                    val result = withContext(Dispatchers.IO) {
                        aiRepository.askAi(
                            messages = listOf(AiMessage(role = "user", text = prompt)),
                            imageBitmap = bitmap,
                            isScreenScan = true
                        )
                    }
                    _isOcrLoading.value = false
                    when (result) {
                        is AiResult.Success -> {
                            _ocrExtractedText.value = result.text.trim()
                        }
                        is AiResult.Error -> {
                            _ocrExtractedText.value = "Failed to extract text: ${result.message}"
                        }
                    }
                } else {
                    _isOcrLoading.value = false
                    Toast.makeText(this@FloatingAssistantService, "Screen capture failed", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // Fallback to standard permission
            ScreenCapturePermissionActivity.requestPermission(
                context = this@FloatingAssistantService,
                onGranted = { resultCode, data ->
                    promoteToMediaProjectionFgs()
                    serviceScope.launch {
                        val bitmap = screenCaptureHelper.captureFrame(resultCode, data, cropRect)
                        demoteFromMediaProjectionFgs()
                        showOcrGrabber()

                        if (bitmap != null) {
                            val prompt = "Extract all readable text, questions, options, captions, or paragraphs visible in this image accurately. Return only the extracted text line by line."
                            val result = withContext(Dispatchers.IO) {
                                aiRepository.askAi(
                                    messages = listOf(AiMessage(role = "user", text = prompt)),
                                    imageBitmap = bitmap,
                                    isScreenScan = true
                                )
                            }
                            _isOcrLoading.value = false
                            when (result) {
                                is AiResult.Success -> {
                                    _ocrExtractedText.value = result.text.trim()
                                }
                                is AiResult.Error -> {
                                    _ocrExtractedText.value = "Failed to extract text: ${result.message}"
                                }
                            }
                        } else {
                            _isOcrLoading.value = false
                            Toast.makeText(this@FloatingAssistantService, "Screen capture failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDenied = {
                    showBubble()
                    _isOcrLoading.value = false
                    Toast.makeText(this@FloatingAssistantService, "Screen capture permission required", Toast.LENGTH_SHORT).show()
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
        hideQuickHud()
        _isHudLoading.value = true
        _hudSolutionText.value = null
        _hudTitle.value = "Solving Screen..."

        serviceScope.launch {
            // Check if zero-dialog Accessibility capture is available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && OmniAccessibilityService.isServiceRunning()) {
                kotlinx.coroutines.delay(100)
                val bitmap = OmniAccessibilityService.captureScreen(cropRect)
                showQuickHud()

                if (bitmap != null) {
                    val prompt = "Give a concise, direct, accurate solution / answer and key steps for the question/problem visible on this screen. Be clear and quick."
                    val result = withContext(Dispatchers.IO) {
                        aiRepository.askAi(
                            messages = listOf(AiMessage(role = "user", text = prompt)),
                            imageBitmap = bitmap,
                            isScreenScan = true
                        )
                    }
                    _isHudLoading.value = false
                    when (result) {
                        is AiResult.Success -> {
                            _hudTitle.value = "Instant Solution"
                            _hudSolutionText.value = result.text.trim()
                        }
                        is AiResult.Error -> {
                            _hudTitle.value = "Error Solving"
                            _hudSolutionText.value = "⚠️ ${result.message}"
                        }
                    }
                } else {
                    _isHudLoading.value = false
                    Toast.makeText(this@FloatingAssistantService, "Screen capture failed", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // Fallback to standard permission
            ScreenCapturePermissionActivity.requestPermission(
                context = this@FloatingAssistantService,
                onGranted = { resultCode, data ->
                    promoteToMediaProjectionFgs()
                    serviceScope.launch {
                        val bitmap = screenCaptureHelper.captureFrame(resultCode, data, cropRect)
                        demoteFromMediaProjectionFgs()
                        showQuickHud()

                        if (bitmap != null) {
                            val prompt = "Give a concise, direct, accurate solution / answer and key steps for the question/problem visible on this screen. Be clear and quick."
                            val result = withContext(Dispatchers.IO) {
                                aiRepository.askAi(
                                    messages = listOf(AiMessage(role = "user", text = prompt)),
                                    imageBitmap = bitmap,
                                    isScreenScan = true
                                )
                            }
                            _isHudLoading.value = false
                            when (result) {
                                is AiResult.Success -> {
                                    _hudTitle.value = "Instant Solution"
                                    _hudSolutionText.value = result.text.trim()
                                }
                                is AiResult.Error -> {
                                    _hudTitle.value = "Error Solving"
                                    _hudSolutionText.value = "⚠️ ${result.message}"
                                }
                            }
                        } else {
                            _isHudLoading.value = false
                            Toast.makeText(this@FloatingAssistantService, "Screen capture failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDenied = {
                    showBubble()
                    _isHudLoading.value = false
                    Toast.makeText(this@FloatingAssistantService, "Screen capture permission required", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // ==========================================
    // AREA CROP SELECTOR OVERLAY
    // ==========================================

    fun showCropOverlay() {
        hidePopup()
        hideBubble()
        hideOcrGrabber()
        hideQuickHud()

        if (cropOverlayView != null) {
            try {
                cropOverlayView?.visibility = View.VISIBLE
                return
            } catch (e: Exception) {
                hideCropOverlay()
            }
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
        startOcrTextExtraction(cropRect = null)
    }

    /**
     * Captures the screen (or cropped region) and analyzes it with AI.
     * Uses OmniAccessibilityService for instant 100% zero-dialog captures.
     * Falls back to standard MediaProjection if accessibility service is not yet enabled.
     */
    fun startScreenScan(cropRect: Rect?) {
        _statusText.value = "Preparing Screen Capture..."
        hidePopup()
        hideBubble()
        hideOcrGrabber()
        hideQuickHud()

        serviceScope.launch {
            // Check if zero-dialog Accessibility capture is available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && OmniAccessibilityService.isServiceRunning()) {
                _statusText.value = "Capturing screen area..."
                // Small delay to let overlay views fully disappear before snapping
                kotlinx.coroutines.delay(100)
                val bitmap = OmniAccessibilityService.captureScreen(cropRect)
                showPopup()

                if (bitmap != null) {
                    _statusText.value = "Analyzing screen with AI..."
                    sendScreenAnalysisRequest(bitmap, prompt = "Scan and solve/explain this screen content accurately.")
                } else {
                    _statusText.value = null
                    Toast.makeText(this@FloatingAssistantService, "Could not capture screen area", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // If accessibility is not turned on in system settings, explain or fallback
            if (!OmniAccessibilityService.isAccessibilityEnabled(this@FloatingAssistantService)) {
                Toast.makeText(
                    this@FloatingAssistantService,
                    "Tip: Enable OmniAI in Accessibility Settings to eliminate all recording popups!",
                    Toast.LENGTH_LONG
                ).show()
            }

            // Fallback to MediaProjection
            ScreenCapturePermissionActivity.requestPermission(
                context = this@FloatingAssistantService,
                onGranted = { resultCode, data ->
                    promoteToMediaProjectionFgs()
                    serviceScope.launch {
                        _statusText.value = "Capturing screen..."
                        val bitmap = screenCaptureHelper.captureFrame(resultCode, data, cropRect)
                        demoteFromMediaProjectionFgs()
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
        _isGenerating.value = true
        _statusText.value = "Analyzing screen..."

        serviceScope.launch {
            val app = application as OmniAIApplication
            val convId = _currentConversationId.value

            val imageBase64 = withContext(Dispatchers.Default) {
                try {
                    val maxDim = 800
                    var scaled = bitmap
                    if (bitmap.width > maxDim || bitmap.height > maxDim) {
                        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                        val newW = if (bitmap.width > bitmap.height) maxDim else (maxDim * ratio).toInt()
                        val newH = if (bitmap.width > bitmap.height) (maxDim / ratio).toInt() else maxDim
                        scaled = Bitmap.createScaledBitmap(bitmap, newW.coerceAtLeast(1), newH.coerceAtLeast(1), true)
                    }
                    val stream = java.io.ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                    android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
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

            val imageBase64 = withContext(Dispatchers.Default) {
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
                        val stream = java.io.ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                        android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
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

    override fun onDestroy() {
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
        var activeServiceInstance: FloatingAssistantService? = null
            private set

        fun isRunning(): Boolean = activeServiceInstance != null
    }
}
