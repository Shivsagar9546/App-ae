package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AiBubbleGradientEnd
import com.example.ui.theme.AiBubbleGradientStart

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingBubbleView(
    onBubbleClick: () -> Unit,
    onScanScreen: () -> Unit,
    onAreaScan: () -> Unit,
    onVoiceClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(8.dp)
    ) {
        // Quick Actions Menu on Long Press
        AnimatedVisibility(
            visible = showMenu,
            enter = fadeIn() + scaleIn(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut() + scaleOut()
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .width(180.dp)
                    .testTag("floating_bubble_menu")
            ) {
                Column(
                    modifier = Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    BubbleMenuItem(
                        icon = Icons.Default.OpenInFull,
                        label = "Open AI Popup",
                        onClick = {
                            showMenu = false
                            onBubbleClick()
                        }
                    )
                    BubbleMenuItem(
                        icon = Icons.Default.Screenshot,
                        label = "Scan Screen",
                        onClick = {
                            showMenu = false
                            onScanScreen()
                        }
                    )
                    BubbleMenuItem(
                        icon = Icons.Default.CropFree,
                        label = "Select Area",
                        onClick = {
                            showMenu = false
                            onAreaScan()
                        }
                    )
                    BubbleMenuItem(
                        icon = Icons.Default.Mic,
                        label = "Voice Prompt",
                        onClick = {
                            showMenu = false
                            onVoiceClick()
                        }
                    )
                    BubbleMenuItem(
                        icon = Icons.Default.Settings,
                        label = "Settings",
                        onClick = {
                            showMenu = false
                            onOpenSettings()
                        }
                    )
                    BubbleMenuItem(
                        icon = Icons.Default.Close,
                        label = "Close Bubble",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            showMenu = false
                            onClose()
                        }
                    )
                }
            }
        }

        // Main Draggable Bubble Pill
        Surface(
            shape = RoundedCornerShape(26.dp),
            shadowElevation = 10.dp,
            modifier = Modifier
                .shadow(12.dp, RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp))
                .combinedClickable(
                    onClick = {
                        if (showMenu) showMenu = false else onBubbleClick()
                    },
                    onLongClick = {
                        showMenu = !showMenu
                    }
                )
                .testTag("floating_ai_bubble")
        ) {
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(AiBubbleGradientStart, AiBubbleGradientEnd)
                        )
                    )
                    .border(
                        1.5.dp,
                        Color.White.copy(alpha = 0.4f),
                        RoundedCornerShape(26.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI Assistant",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "AI ✨",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun BubbleMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier.height(36.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                color = tint,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
