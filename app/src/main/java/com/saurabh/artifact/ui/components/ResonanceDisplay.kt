package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.model.ReactionVisibilityMode
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.ReflectionWhite
import com.saurabh.artifact.ui.theme.MistGray

/**
 * RESONANCE DISPLAY
 * Converts raw counts into emotionally human summaries or exact counts based on visibility mode.
 */
@Composable
fun ResonanceDisplay(
    counts: ArtifactReactionCounts?,
    modifier: Modifier = Modifier
) {
    if (counts == null || counts.totalCount == 0) return

    val visibility = counts.visibility
    val resonanceText = when (visibility) {
        ReactionVisibilityMode.APPROXIMATE -> {
            getApproximateText(counts.totalCount)
        }
        ReactionVisibilityMode.VISIBLE -> {
            "${counts.totalCount} souls felt this deeply."
        }
        ReactionVisibilityMode.CREATOR_ONLY, ReactionVisibilityMode.HIDDEN -> {
            null // Handle based on context (creator sees breakdown elsewhere)
        }
    }

    resonanceText?.let { text ->
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
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MistGray.copy(alpha = 0.8f)
            )
        }
    }
}

private fun getApproximateText(total: Int): String {
    return when {
        total <= 0 -> ""
        total == 1 -> "Another soul felt this."
        total < 5 -> "A few people felt this."
        total < 20 -> "Several people related to this."
        else -> "Many listeners resonated with this."
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
            text = counts.aiSummary ?: getApproximateText(counts.totalCount),
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
