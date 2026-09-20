package com.example.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

@android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
class FloatingImagePickerActivity : ComponentActivity() {

    private fun safeFinish() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private val getContentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        handleImageUri(uri)
        safeFinish()
    }
 
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            captureScreen(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture cancelled", Toast.LENGTH_SHORT).show()
            FloatingAssistantService.activeServiceInstance?.showBubble()
            safeFinish()
        }
    }

    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImageUri(uri)
            safeFinish()
        } else {
            // Fallback to GetContent if cancelled or empty
            safeFinish()
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                takePhotoLauncher.launch(null)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera not available: ${e.message}", Toast.LENGTH_SHORT).show()
                safeFinish()
            }
        } else {
            Toast.makeText(this, "Camera permission needed to take photos", Toast.LENGTH_SHORT).show()
            safeFinish()
        }
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val scaled = scaleDownBitmap(bitmap, 1280)
            onImageSelectedCallback?.invoke(scaled)
        }
        safeFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_GALLERY
        when (mode) {
            MODE_SCREEN_CAPTURE -> {
                try {
                    val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                        mediaProjectionManager.createScreenCaptureIntent(config)
                    } else {
                        mediaProjectionManager.createScreenCaptureIntent()
                    }
                    screenCaptureLauncher.launch(captureIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Screen capture not supported: ${e.message}", Toast.LENGTH_SHORT).show()
                    FloatingAssistantService.activeServiceInstance?.showBubble()
                    safeFinish()
                }
            }
            MODE_CAMERA -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        takePhotoLauncher.launch(null)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Cannot open camera: ${e.message}", Toast.LENGTH_SHORT).show()
                        safeFinish()
                    }
                } else {
                    requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            else -> {
                try {
                    pickVisualMediaLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                } catch (e: Exception) {
                    try {
                        getContentLauncher.launch("image/*")
                    } catch (e2: Exception) {
                        Toast.makeText(this, "No image picker available: ${e2.localizedMessage}", Toast.LENGTH_SHORT).show()
                        safeFinish()
                    }
                }
            }
        }
    }

    private fun handleImageUri(uri: Uri?) {
        if (uri == null) {
            safeFinish()
            return
        }
        try {
            // First decode with inJustDecodeBounds to prevent OutOfMemoryError on large images
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }

            var sampleSize = 1
            val maxDim = 1280
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth > maxDim || origHeight > maxDim) {
                val halfWidth = origWidth / 2
                val halfHeight = origHeight / 2
                while ((halfWidth / sampleSize) >= maxDim && (halfHeight / sampleSize) >= maxDim) {
                    sampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }

            if (bitmap != null) {
                val scaled = scaleDownBitmap(bitmap, 1280)
                onImageSelectedCallback?.invoke(scaled)
            } else {
                Toast.makeText(this, "Could not load image file", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Error loading image: ${t.localizedMessage}", Toast.LENGTH_SHORT).show()
        } finally {
            safeFinish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            onImageSelectedCallback = null
        }
    }

    private fun captureScreen(resultCode: Int, resultData: Intent) {
        try {
            val fgs = FloatingAssistantService.activeServiceInstance
            fgs?.promoteToMediaProjectionFgs()

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpManager.getMediaProjection(resultCode, resultData) ?: run {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                fgs?.showBubble()
                safeFinish()
                return
            }

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = windowManager.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            ) ?: run {
                projection.stop()
                imageReader.close()
                fgs?.demoteFromMediaProjectionFgs()
                fgs?.showBubble()
                Toast.makeText(this, "Virtual display setup failed", Toast.LENGTH_SHORT).show()
                safeFinish()
                return
            }

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                try {
                    val image = imageReader.acquireLatestImage()
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

                        val cleanBitmap = if (bitmap.width != width) {
                            Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        } else {
                            bitmap
                        }

                        virtualDisplay.release()
                        projection.stop()
                        imageReader.close()
                        fgs?.demoteFromMediaProjectionFgs()

                        onImageSelectedCallback?.invoke(cleanBitmap)
                    } else {
                        // Retry once
                        handler.postDelayed({
                            try {
                                val img2 = imageReader.acquireLatestImage()
                                if (img2 != null) {
                                    val planes = img2.planes
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
                                    img2.close()

                                    val cleanBitmap = if (bitmap.width != width) {
                                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                                    } else {
                                        bitmap
                                    }

                                    virtualDisplay.release()
                                    projection.stop()
                                    imageReader.close()
                                    fgs?.demoteFromMediaProjectionFgs()

                                    onImageSelectedCallback?.invoke(cleanBitmap)
                                } else {
                                    virtualDisplay.release()
                                    projection.stop()
                                    imageReader.close()
                                    fgs?.demoteFromMediaProjectionFgs()
                                    fgs?.showBubble()
                                    Toast.makeText(this, "Screen capture timed out", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                fgs?.demoteFromMediaProjectionFgs()
                                fgs?.showBubble()
                                Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                safeFinish()
                            }
                        }, 50)
                        return@postDelayed
                    }
                } catch (e: Exception) {
                    fgs?.demoteFromMediaProjectionFgs()
                    fgs?.showBubble()
                    Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    safeFinish()
                }
            }, 150)

        } catch (e: Exception) {
            FloatingAssistantService.activeServiceInstance?.demoteFromMediaProjectionFgs()
            Toast.makeText(this, "Screen capture setup failed: ${e.message}", Toast.LENGTH_SHORT).show()
            safeFinish()
        }
    }

    private fun scaleDownBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        return if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val newW = if (bitmap.width > bitmap.height) maxDim else (maxDim * ratio).toInt()
            val newH = if (bitmap.width > bitmap.height) (maxDim / ratio).toInt() else maxDim
            Bitmap.createScaledBitmap(bitmap, newW.coerceAtLeast(1), newH.coerceAtLeast(1), true)
        } else {
            bitmap
        }
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_GALLERY = "mode_gallery"
        const val MODE_CAMERA = "mode_camera"
        const val MODE_SCREEN_CAPTURE = "mode_screen_capture"

        private var onImageSelectedCallback: ((Bitmap) -> Unit)? = null

        fun launchScreenCapture(context: Context, onPicked: (Bitmap) -> Unit) {
            onImageSelectedCallback = onPicked
            val intent = Intent(context, FloatingImagePickerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SCREEN_CAPTURE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }

        fun launchGalleryPicker(context: Context, onPicked: (Bitmap) -> Unit) {
            onImageSelectedCallback = onPicked
            val intent = Intent(context, FloatingImagePickerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_GALLERY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }

        fun launchCameraPicker(context: Context, onPicked: (Bitmap) -> Unit) {
            onImageSelectedCallback = onPicked
            val intent = Intent(context, FloatingImagePickerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_CAMERA)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}
