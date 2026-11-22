package com.saurabh.artifact.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.ui.theme.ArtifactTheme

/**
 * RESONANCE DISPLAY
 * Converts raw counts into emotionally human summaries or exact counts based on visibility mode.
 * Uses the "Calm Anonymous Resonance Architecture" to prioritize atmosphere over volume.
 *
 * Supports both interactive (navigating to resonator list) and non-interactive (inert summary) modes.
 * Interactive instances guarantee a minimum 48dp x 48dp touch target with Button role semantics.
 * Non-interactive instances omit click semantics so screen readers do not treat them as actionable.
 */
@Composable
fun ResonanceDisplay(
    modifier: Modifier = Modifier,
    counts: ArtifactReactionCounts? = null,
    summary: String? = null,
    isOwner: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = (onClick != null),
) {
    val finalSummary = summary ?: counts?.getFuzzySummary(isOwner) ?: ""

    if (finalSummary.isNotEmpty()) {
        val isInteractive = enabled && (onClick != null)
        val clickAction = if (isInteractive) onClick else null

        val accessibilityDescription = if (clickAction != null) {
            "$finalSummary. View resonating listeners."
        } else {
            finalSummary
        }

        Box(
            modifier = modifier
                .then(
                    if (clickAction != null) {
                        Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "View resonating listeners",
                                onClick = clickAction,
                            )
                    } else {
                        Modifier
                    }
                )
                .semantics {
                    contentDescription = accessibilityDescription
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    tint = ArtifactTheme.colors.onSurfaceAura,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = finalSummary,
                    style = ArtifactTheme.typography.labelSmall,
                    color = ArtifactTheme.colors.onSurfaceAura
                )
            }
        }
    }
}
