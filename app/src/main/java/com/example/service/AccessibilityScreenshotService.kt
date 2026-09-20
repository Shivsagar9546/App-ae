package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

class AccessibilityScreenshotService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        activeInstance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        activeInstance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    companion object {
        var activeInstance: AccessibilityScreenshotService? = null

        fun isEnabled(): Boolean {
            return activeInstance != null
        }

        @RequiresApi(Build.VERSION_CODES.R)
        fun captureScreenSilently(
            executor: Executor,
            onSuccess: (Bitmap) -> Unit,
            onFailure: (String) -> Unit
        ) {
            val service = activeInstance
            if (service == null) {
                onFailure("Accessibility Service not enabled")
                return
            }

            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bmp = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            if (bmp != null) {
                                val softwareBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
                                hardwareBuffer.close()
                                onSuccess(softwareBmp)
                            } else {
                                hardwareBuffer.close()
                                onFailure("Failed to wrap hardware buffer")
                            }
                        } catch (e: Exception) {
                            onFailure("Error wrapping bitmap: ${e.message}")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal error"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "No accessibility access"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Interval too short"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display ID"
                            else -> "Unknown error code: $errorCode"
                        }
                        onFailure(reason)
                    }
                }
            )
        }
    }
}
