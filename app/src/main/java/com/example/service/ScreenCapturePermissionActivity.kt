package com.example.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

@android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
class ScreenCapturePermissionActivity : ComponentActivity() {

    private var handled = false

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handled = true
        val granted = onScreenCapturePermissionGranted
        val denied = onScreenCapturePermissionDenied
        onScreenCapturePermissionGranted = null
        onScreenCapturePermissionDenied = null

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            granted?.invoke(result.resultCode, result.data!!)
        } else {
            denied?.invoke()
        }
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // On Android 14+, defaultDisplay config ensures the entire display is captured
                // directly rather than opening the confusing "Choose app to share" single-app picker dialog!
                val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                projectionManager.createScreenCaptureIntent(config)
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            captureLauncher.launch(intent)
        } catch (e: Exception) {
            handled = true
            onScreenCapturePermissionDenied?.invoke()
            onScreenCapturePermissionGranted = null
            onScreenCapturePermissionDenied = null
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!handled) {
            onScreenCapturePermissionDenied?.invoke()
        }
        onScreenCapturePermissionGranted = null
        onScreenCapturePermissionDenied = null
    }

    companion object {
        var onScreenCapturePermissionGranted: ((resultCode: Int, data: Intent) -> Unit)? = null
        var onScreenCapturePermissionDenied: (() -> Unit)? = null

        // Cache last granted permission to avoid repeated dialog prompts when supported
        private var lastResultCode: Int? = null
        private var lastResultData: Intent? = null

        fun clearCachedPermission() {
            lastResultCode = null
            lastResultData = null
        }

        fun requestPermission(
            context: Context,
            onGranted: (resultCode: Int, data: Intent) -> Unit,
            onDenied: () -> Unit
        ) {
            val cachedCode = lastResultCode
            val cachedData = lastResultData
            if (cachedCode != null && cachedData != null) {
                // If permission was already approved in this session, attempt direct reuse
                try {
                    val clonedData = cachedData.clone() as? Intent ?: cachedData
                    onGranted(cachedCode, clonedData)
                    return
                } catch (e: Exception) {
                    clearCachedPermission()
                }
            }

            onScreenCapturePermissionGranted = { code, data ->
                lastResultCode = code
                lastResultData = data
                onGranted(code, data)
            }
            onScreenCapturePermissionDenied = {
                clearCachedPermission()
                onDenied()
            }
            val intent = Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}
