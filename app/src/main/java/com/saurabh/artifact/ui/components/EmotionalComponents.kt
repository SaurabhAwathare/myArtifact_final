package com.saurabh.artifact.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * PetalChip: A custom organic-shaped chip for filters.
 * Features one unique rounded corner to feel less "industrial".
 */
@Composable
fun PetalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emoji: String? = null
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    val textColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .clip(
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 4.dp, // The "Petal" tip
                    bottomEnd = 16.dp,
                    bottomStart = 16.dp
                )
            )
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) {
                Text(text = emoji, modifier = Modifier.padding(end = 4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = textColor
            )
        }
    }
}

/**
 * QuietTab: A minimalist tab implementation for switching feeds.
 */
@Composable
fun QuietTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (selected) {
                Color.White
            } else {
                Color.White.copy(alpha = 0.5f)
            }
        )
        Spacer(Modifier.height(4.dp))
        // Warm underline animation (static for now, but styled correctly)
        if (selected) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(com.saurabh.artifact.ui.theme.GoldAura500)
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
