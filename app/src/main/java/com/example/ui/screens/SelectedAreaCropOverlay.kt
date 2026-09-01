package com.example.ui.screens

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

@Composable
fun SelectedAreaCropOverlay(
    onAreaSelected: (Rect?) -> Unit,
    onCancel: () -> Unit
) {
    var startOffset by remember { mutableStateOf<Offset?>(null) }
    var currentOffset by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        startOffset = offset
                        currentOffset = offset
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        currentOffset = change.position
                    },
                    onDragEnd = {
                        // Keep current selection
                    }
                )
            }
            .testTag("area_crop_overlay")
    ) {
        // Draw dimmed canvas with transparent cutout rectangle
        Canvas(modifier = Modifier.fillMaxSize()) {
            val start = startOffset
            val end = currentOffset

            // Draw semi-transparent dark backdrop
            drawRect(
                color = Color(0x99000000),
                size = size
            )

            if (start != null && end != null) {
                val left = min(start.x, end.x)
                val top = min(start.y, end.y)
                val right = max(start.x, end.x)
                val bottom = max(start.y, end.y)
                val rectWidth = right - left
                val rectHeight = bottom - top

                if (rectWidth > 10 && rectHeight > 10) {
                    // Cut out selected area
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(left, top),
                        size = Size(rectWidth, rectHeight),
                        blendMode = BlendMode.Clear
                    )

                    // Glowing bounding box border
                    drawRect(
                        color = Color(0xFF6366F1),
                        topLeft = Offset(left, top),
                        size = Size(rectWidth, rectHeight),
                        style = Stroke(width = 3.dp.toPx())
                    )

                    // Corner indicators
                    val cornerLen = 20.dp.toPx()
                    val strokeW = 5.dp.toPx()
                    // Top-Left
                    drawLine(Color(0xFF38BDF8), Offset(left, top), Offset(left + cornerLen, top), strokeW)
                    drawLine(Color(0xFF38BDF8), Offset(left, top), Offset(left, top + cornerLen), strokeW)
                    // Top-Right
                    drawLine(Color(0xFF38BDF8), Offset(right, top), Offset(right - cornerLen, top), strokeW)
                    drawLine(Color(0xFF38BDF8), Offset(right, top), Offset(right, top + cornerLen), strokeW)
                    // Bottom-Left
                    drawLine(Color(0xFF38BDF8), Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW)
                    drawLine(Color(0xFF38BDF8), Offset(left, bottom), Offset(left, bottom - cornerLen), strokeW)
                    // Bottom-Right
                    drawLine(Color(0xFF38BDF8), Offset(right, bottom), Offset(right - cornerLen, bottom), strokeW)
                    drawLine(Color(0xFF38BDF8), Offset(right, bottom), Offset(right, bottom - cornerLen), strokeW)
                }
            }
        }

        // Top instruction pill
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 20.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CropFree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Drag finger over question or area to scan",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Bottom action buttons
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 30.dp, start = 20.dp, end = 20.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("crop_cancel_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        val start = startOffset
                        val end = currentOffset
                        if (start != null && end != null) {
                            val left = min(start.x, end.x).toInt()
                            val top = min(start.y, end.y).toInt()
                            val right = max(start.x, end.x).toInt()
                            val bottom = max(start.y, end.y).toInt()
                            if (right - left > 20 && bottom - top > 20) {
                                onAreaSelected(Rect(left, top, right, bottom))
                                return@Button
                            }
                        }
                        // Default to full screen scan if no rectangle was drawn
                        onAreaSelected(null)
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("crop_confirm_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Confirm",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Selected Area", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
