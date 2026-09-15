package com.saurabh.artifact.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.ui.theme.ArtifactTheme

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
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = true) { onClick() }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = ArtifactTheme.colors.waveformActive.copy(alpha = 0.8f),
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = finalSummary,
                style = ArtifactTheme.typography.labelSmall,
                color = ArtifactTheme.colors.waveformActive.copy(alpha = 0.8f)
            )
        }
    }
}
