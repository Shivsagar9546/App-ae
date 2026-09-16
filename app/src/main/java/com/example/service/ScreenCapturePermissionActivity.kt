package com.example.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

@android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
class ScreenCapturePermissionActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        onScreenCapturePermissionGranted = null
        onScreenCapturePermissionDenied = null
    }

    companion object {
        var onScreenCapturePermissionGranted: ((resultCode: Int, data: Intent) -> Unit)? = null
        var onScreenCapturePermissionDenied: (() -> Unit)? = null

        fun requestPermission(
            context: Context,
            onGranted: (resultCode: Int, data: Intent) -> Unit,
            onDenied: () -> Unit
        ) {
            onScreenCapturePermissionGranted = onGranted
            onScreenCapturePermissionDenied = onDenied
            val intent = Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}
