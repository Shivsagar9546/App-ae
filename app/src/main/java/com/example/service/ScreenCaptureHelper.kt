package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class ScreenCaptureHelper(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    fun createProjectionIntent(): Intent {
        return projectionManager.createScreenCaptureIntent()
    }

    suspend fun captureFrame(resultCode: Int, data: Intent, cropRect: Rect? = null): Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            var mediaProjection: MediaProjection? = null
            var virtualDisplay: VirtualDisplay? = null
            var imageReader: ImageReader? = null
            var backgroundThread: HandlerThread? = null

            val isResumed = AtomicBoolean(false)

            fun cleanup() {
                try {
                    imageReader?.setOnImageAvailableListener(null, null)
                    imageReader?.close()
                } catch (e: Exception) {}
                try {
                    virtualDisplay?.release()
                } catch (e: Exception) {}
                try {
                    mediaProjection?.stop()
                } catch (e: Exception) {}
                try {
                    backgroundThread?.quitSafely()
                } catch (e: Exception) {}
            }

            continuation.invokeOnCancellation {
                cleanup()
            }

            try {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                if (mediaProjection == null) {
                    ScreenCapturePermissionActivity.clearCachedPermission()
                    if (isResumed.compareAndSet(false, true)) {
                        continuation.resume(null)
                    }
                    return@suspendCancellableCoroutine
                }

                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)

                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                // Use background thread for fast image capture processing so UI thread does not lag or hang
                backgroundThread = HandlerThread("ScreenCaptureBackgroundThread").apply { start() }
                val backgroundHandler = Handler(backgroundThread.looper)

                // Android 14+ requires registering a callback before creating virtual display
                val projectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        cleanup()
                    }
                }
                mediaProjection.registerCallback(projectionCallback, backgroundHandler)

                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

                imageReader.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage() ?: reader.acquireNextImage()
                        if (image != null) {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)
                            image.close()

                            // Crop bitmap to screen bounds without padding
                            val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            if (bitmap != cleanBitmap) {
                                bitmap.recycle()
                            }

                            // If user selected a specific area rectangle, crop to that area
                            val croppedBitmap = if (cropRect != null && cropRect.width() > 10 && cropRect.height() > 10) {
                                // Scale cropRect if captured bitmap size differs from metrics
                                val scaleX = cleanBitmap.width.toFloat() / width.toFloat()
                                val scaleY = cleanBitmap.height.toFloat() / height.toFloat()

                                val left = (cropRect.left * scaleX).toInt().coerceIn(0, cleanBitmap.width - 1)
                                val top = (cropRect.top * scaleY).toInt().coerceIn(0, cleanBitmap.height - 1)
                                val right = (cropRect.right * scaleX).toInt().coerceIn(left + 1, cleanBitmap.width)
                                val bottom = (cropRect.bottom * scaleY).toInt().coerceIn(top + 1, cleanBitmap.height)
                                val cropW = (right - left).coerceAtLeast(1)
                                val cropH = (bottom - top).coerceAtLeast(1)

                                val cropped = Bitmap.createBitmap(cleanBitmap, left, top, cropW, cropH)
                                if (cleanBitmap != cropped) {
                                    cleanBitmap.recycle()
                                }
                                cropped
                            } else {
                                cleanBitmap
                            }

                            // Memory & Thermal Optimization: Downscale if larger than 1280px to prevent CPU spikes / RAM heating
                            val maxDim = 1280
                            val finalBitmap = if (croppedBitmap.width > maxDim || croppedBitmap.height > maxDim) {
                                val scale = maxDim.toFloat() / maxOf(croppedBitmap.width, croppedBitmap.height)
                                val targetW = (croppedBitmap.width * scale).toInt().coerceAtLeast(1)
                                val targetH = (croppedBitmap.height * scale).toInt().coerceAtLeast(1)
                                val scaled = Bitmap.createScaledBitmap(croppedBitmap, targetW, targetH, true)
                                if (croppedBitmap != scaled) {
                                    croppedBitmap.recycle()
                                }
                                scaled
                            } else {
                                croppedBitmap
                            }

                            cleanup()

                            if (isResumed.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resume(finalBitmap)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ScreenCaptureHelper", "Error acquiring frame", e)
                        cleanup()
                        if (isResumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }, backgroundHandler)

                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "OmniAIScreenCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    backgroundHandler
                )

                // Timeout fallback after 6 seconds if image listener doesn't fire
                backgroundHandler.postDelayed({
                    cleanup()
                    if (isResumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(null)
                    }
                }, 6000)

            } catch (e: Exception) {
                android.util.Log.e("ScreenCaptureHelper", "Error starting projection", e)
                ScreenCapturePermissionActivity.clearCachedPermission()
                cleanup()
                if (isResumed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }
}
