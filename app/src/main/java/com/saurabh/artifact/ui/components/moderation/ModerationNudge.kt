package com.saurabh.artifact.ui.components.moderation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.Spacing

enum class NudgeLevel {
    INFO,
    WARNING,
    CRITICAL
}

@Composable
fun ModerationNudge(
    visible: Boolean,
    message: String,
    modifier: Modifier = Modifier,
    level: NudgeLevel = NudgeLevel.INFO,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        val containerColor = when (level) {
            NudgeLevel.INFO -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            NudgeLevel.WARNING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
            NudgeLevel.CRITICAL -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
        }

        val contentColor = when (level) {
            NudgeLevel.INFO -> MaterialTheme.colorScheme.onSecondaryContainer
            NudgeLevel.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
            NudgeLevel.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
        }

        val icon = when (level) {
            NudgeLevel.INFO -> Icons.Rounded.Info
            NudgeLevel.WARNING -> Icons.Rounded.Warning
            NudgeLevel.CRITICAL -> Icons.Rounded.Warning
        }

        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.Small)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
