package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun Base64ImageView(
    base64String: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = "Message attachment",
    fillWidth: Boolean = true
) {
    var isViewerOpen by remember { mutableStateOf(false) }

    val bitmap: Bitmap? = remember(base64String) {
        try {
            if (base64String.isNotBlank() && (base64String.startsWith("/") || base64String.startsWith("file://"))) {
                val filePath = base64String.replace("file://", "")
                BitmapFactory.decodeFile(filePath)
            } else {
                val cleanBase64 = if (base64String.contains(",")) {
                    base64String.substringAfter(",")
                } else {
                    base64String
                }
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            }
        } catch (e: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Box(
            modifier = modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .heightIn(max = 240.dp)
                    .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { isViewerOpen = true }
            )
        }

        // Fullscreen Lightbox / Popup Image Viewer
        if (isViewerOpen) {
            Popup(
                onDismissRequest = { isViewerOpen = false },
                properties = PopupProperties(
                    focusable = true,
                    excludeFromSystemGesture = true
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.95f))
                        .clickable { isViewerOpen = false },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Expanded image view",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    )

                    // Floating Round Close Button
                    IconButton(
                        onClick = { isViewerOpen = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 40.dp, end = 20.dp)
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
