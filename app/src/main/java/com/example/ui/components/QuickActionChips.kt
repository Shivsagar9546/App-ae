package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

data class QuickActionItem(
    val title: String,
    val prompt: String,
    val icon: ImageVector
)

val defaultQuickActions = listOf(
    QuickActionItem("Get Answer", "Solve and provide the direct answer to this screen.", Icons.Default.AutoAwesome),
    QuickActionItem("Explain", "Explain what is shown on this screen in simple terms.", Icons.Default.Lightbulb),
    QuickActionItem("Solve Step-by-Step", "Solve this step-by-step with clear logic.", Icons.Default.Calculate),
    QuickActionItem("Translate", "Translate the text visible in this screen to Hindi & English.", Icons.Default.Translate),
    QuickActionItem("Summarize", "Summarize the key points visible on this screen.", Icons.Default.Description),
    QuickActionItem("Extract Text", "Extract and transcribe all text from this screen clearly.", Icons.Default.MenuBook),
    QuickActionItem("What should I do?", "What action should I take next based on what's visible on this screen?", Icons.Default.HelpOutline)
)

@Composable
fun QuickActionChips(
    onActionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    actions: List<QuickActionItem> = defaultQuickActions
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEachIndexed { index, action ->
            AssistChip(
                onClick = { onActionSelected(action.prompt) },
                label = { Text(action.title, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.title,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                shape = RoundedCornerShape(20.dp),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                ),
                modifier = Modifier.testTag("quick_action_chip_$index")
            )
        }
    }
}
