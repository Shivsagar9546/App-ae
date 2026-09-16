package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * High-performance Accessibility Service that extracts visible digital text from the active screen.
 * This runs locally with zero media projection dialogs ("Start recording or casting with OmniAI?").
 */
class ScreenReaderAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "ScreenReaderAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active event tracking if needed
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScreenReaderAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        Log.d(TAG, "ScreenReaderAccessibilityService destroyed")
    }

    /**
     * Traverses the current active window's node tree and extracts readable text.
     */
    fun captureActiveScreenText(): String? {
        val rootNode = rootInActiveWindow ?: return null
        val lines = mutableListOf<String>()
        val visited = mutableSetOf<Int>()

        try {
            extractTextFromNode(rootNode, lines, visited, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from active window", e)
        } finally {
            safeRecycle(rootNode)
        }

        val cleaned = lines
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")

        return if (cleaned.isNotBlank()) cleaned else null
    }

    private fun extractTextFromNode(
        node: AccessibilityNodeInfo?,
        lines: MutableList<String>,
        visited: MutableSet<Int>,
        depth: Int
    ) {
        if (node == null || depth > 25) return
        val nodeHash = node.hashCode()
        if (visited.contains(nodeHash)) return
        visited.add(nodeHash)

        // Capture direct text
        node.text?.toString()?.trim()?.let {
            if (it.isNotBlank()) lines.add(it)
        }

        // Capture content descriptions (e.g. icon buttons with labels)
        node.contentDescription?.toString()?.trim()?.let {
            if (it.isNotBlank()) lines.add(it)
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            if (child != null) {
                extractTextFromNode(child, lines, visited, depth + 1)
                safeRecycle(child)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            try {
                node?.recycle()
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "ScreenReaderA11y"
        var instance: ScreenReaderAccessibilityService? = null

        /**
         * Checks if this Accessibility Service is currently active and bound.
         */
        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            try {
                val expectedComponent = "${context.packageName}/${ScreenReaderAccessibilityService::class.java.name}"
                val expectedSimple = ScreenReaderAccessibilityService::class.java.simpleName
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                return enabledServices.contains(expectedComponent) || enabledServices.contains(expectedSimple)
            } catch (e: Exception) {
                return false
            }
        }

        /**
         * Direct deep-link to the Android Accessibility settings page.
         */
        fun openAccessibilitySettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open accessibility settings", e)
            }
        }
    }
}
