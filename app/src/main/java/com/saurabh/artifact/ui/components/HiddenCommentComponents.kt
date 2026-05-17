package com.saurabh.artifact.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.*

/**
 * Notice banner explaining the Hidden Comments Mode.
 * Obsidian + Amber theme for emotional safety.
 */
@Composable
fun HiddenCommentNotice(modifier: Modifier = Modifier) {
    Surface(
        color = Obsidian800,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GoldAura500.copy(alpha = 0.2f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = GoldAura500,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Private Reflection Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = GoldAura400
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your response is a private bridge between you and the creator. It will not be visible to other listeners.",
                style = MaterialTheme.typography.bodyMedium,
                color = ReflectionWhite.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Empty state for the Creator Inbox (The Hearth).
 */
@Composable
fun EmptyHearthState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                tint = GoldAura500.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "The Hearth is quiet.",
                style = MaterialTheme.typography.titleMedium,
                color = ReflectionWhite
            )
            Text(
                text = "Reflections will appear here when listeners respond.",
                style = MaterialTheme.typography.bodySmall,
                color = MistGray
            )
        }
    }
}
