package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Accessibility service that enables zero-dialog, zero-popup instant screen captures.
 * Supported on Android 11 (API 30)+ using takeScreenshot().
 * Removes the intrusive "Start recording or casting" system dialog completely.
 */
class OmniAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op - we only use the service for permission-free screen captures
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        private var instance: OmniAccessibilityService? = null
        private val executor = Executors.newSingleThreadExecutor()

        fun isServiceRunning(): Boolean = instance != null

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expectedComponentName = "${context.packageName}/${OmniAccessibilityService::class.java.name}"
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val accessibilityEnabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
                accessibilityEnabled == 1 && (enabledServices.contains(expectedComponentName) || enabledServices.contains(context.packageName))
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Takes a full-resolution screenshot instantly without any system dialog,
         * and optionally crops it to cropRect.
         */
        suspend fun captureScreen(cropRect: Rect? = null): Bitmap? {
            val service = instance ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return null
            }

            return suspendCancellableCoroutine { continuation ->
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        executor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshotResult: ScreenshotResult) {
                                try {
                                    val hardwareBuffer = screenshotResult.hardwareBuffer
                                    val colorSpace = screenshotResult.colorSpace
                                    val fullBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                    hardwareBuffer.close()

                                    if (fullBitmap == null) {
                                        continuation.resume(null)
                                        return
                                    }

                                    // Software copy so it can be cropped and compressed
                                    val softwareBitmap = fullBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                    fullBitmap.recycle()

                                    if (cropRect != null && softwareBitmap != null) {
                                        val left = cropRect.left.coerceIn(0, softwareBitmap.width - 1)
                                        val top = cropRect.top.coerceIn(0, softwareBitmap.height - 1)
                                        val right = cropRect.right.coerceIn(left + 1, softwareBitmap.width)
                                        val bottom = cropRect.bottom.coerceIn(top + 1, softwareBitmap.height)
                                        val cropW = (right - left).coerceAtLeast(1)
                                        val cropH = (bottom - top).coerceAtLeast(1)

                                        val cropped = Bitmap.createBitmap(softwareBitmap, left, top, cropW, cropH)
                                        if (cropped != softwareBitmap) {
                                            softwareBitmap.recycle()
                                        }
                                        continuation.resume(cropped)
                                    } else {
                                        continuation.resume(softwareBitmap)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("OmniAccessibility", "Error processing screenshot", e)
                                    continuation.resume(null)
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                android.util.Log.w("OmniAccessibility", "takeScreenshot failed: $errorCode")
                                continuation.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    android.util.Log.e("OmniAccessibility", "takeScreenshot invocation error", e)
                    continuation.resume(null)
                }
            }
        }
    }
}
