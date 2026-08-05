package com.saurabh.artifact.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.ui.theme.MistGray

/**
 * RESONANCE DISPLAY
 * Converts raw counts into emotionally human summaries or exact counts based on visibility mode.
 * Uses the "Calm Anonymous Resonance Architecture" to prioritize atmosphere over volume.
 */
@Composable
fun ResonanceDisplay(
    modifier: Modifier = Modifier,
    counts: ArtifactReactionCounts? = null,
    summary: String? = null,
    isOwner: Boolean = false,
    onClick: () -> Unit = {}
) {
    val finalSummary = summary ?: counts?.getFuzzySummary(isOwner) ?: ""

    if (finalSummary.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .padding(vertical = 8.dp)
                .clickable(enabled = true) { onClick() }
        ) {
            Text(
                text = "🐚",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = finalSummary,
                style = MaterialTheme.typography.labelMedium,
                color = MistGray.copy(alpha = 0.8f)
            )
            
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MistGray.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp).padding(start = 2.dp)
            )
        }
    }
}
