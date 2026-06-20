package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
    isOwner: Boolean = false
) {
    val finalSummary = summary ?: counts?.getFuzzySummary(isOwner) ?: ""

    if (finalSummary.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.padding(vertical = 8.dp)
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
        }
    }
}
