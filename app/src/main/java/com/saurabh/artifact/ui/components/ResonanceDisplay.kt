package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.ReflectionWhite
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
    val isZero = finalSummary.isEmpty() && (counts == null || counts.totalCount == 0)

    if (isZero) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = "✨",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = "Be the first to resonate",
                style = MaterialTheme.typography.labelMedium,
                color = MistGray.copy(alpha = 0.5f)
            )
        }
        return
    }

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

@Composable
fun EmotionalInsightCard(
    counts: ArtifactReactionCounts,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Listener Resonance",
            style = MaterialTheme.typography.labelLarge,
            color = GoldAura400
        )
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = counts.aiSummary ?: counts.getFuzzySummary(isOwner = true),
            style = MaterialTheme.typography.bodyLarge,
            color = ReflectionWhite
        )
        
        if (counts.breakdown.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                counts.breakdown.entries.take(3).forEach { (type, count) ->
                    Text(
                        text = "${type}: $count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MistGray
                    )
                }
            }
        }
    }
}
