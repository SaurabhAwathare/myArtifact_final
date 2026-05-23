package com.saurabh.artifact.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

/**
 * A specialized card for Drafts, prioritizing emotional continuity and review progress.
 */
@Composable
fun DraftCard(
    draft: ArtifactDraftEntity,
    onContinueReview: () -> Unit,
    onOptionsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progressPercent = if (draft.durationMs > 0) {
        (draft.maxReviewPositionMs.toFloat() / draft.durationMs).coerceIn(0f, 1f)
    } else 0f

    val isReadyToPublish = draft.draftState == ArtifactDraftState.REVIEW_COMPLETED || 
                           draft.draftState == ArtifactDraftState.REVIEWED

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = ArtifactTheme.colors.surfaceHearth
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Subtle background gradient reflecting progress
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                ArtifactTheme.colors.waveformActive.copy(alpha = 0.05f),
                                Color.Transparent
                            ),
                            startX = 0f,
                            endX = progressPercent * 1000f // Rough mapping for visual effect
                        )
                    )
            )

            Column(modifier = Modifier.padding(Spacing.Large)) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Draft Artifact",
                            style = ArtifactTheme.typography.labelMedium,
                            color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.6f)
                        )
                        Text(
                            text = when {
                                isReadyToPublish -> "Review Complete"
                                progressPercent > 0 -> "${(progressPercent * 100).toInt()}% reviewed"
                                else -> "Ready for review"
                            },
                            style = ArtifactTheme.typography.labelLarge.copy(
                                color = if (isReadyToPublish) ArtifactTheme.colors.waveformActive else ArtifactTheme.colors.onSurfaceMain,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    IconButton(onClick = onOptionsClick) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Draft Options",
                            tint = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.Medium))

                // Waveform Preview (Subtle)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ArtifactTheme.colors.surfaceLoom.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = ArtifactTheme.colors.waveformActive.copy(alpha = 0.2f),
                        modifier = Modifier.size(32.dp)
                    )
                    
                    // Simple progress bar overlay
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.BottomStart)
                            .background(ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.1f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressPercent)
                                .fillMaxHeight()
                                .background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.4f))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.Large))

                // PRIMARY ACTION
                Button(
                    onClick = onContinueReview,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReadyToPublish) 
                            ArtifactTheme.colors.waveformActive 
                        else 
                            ArtifactTheme.colors.waveformActive.copy(alpha = 0.1f),
                        contentColor = if (isReadyToPublish)
                            Color.White
                        else
                            ArtifactTheme.colors.waveformActive
                    ),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Text(
                        text = when {
                            isReadyToPublish -> "Proceed to Publish"
                            progressPercent > 0 -> "Continue Review"
                            else -> "Start Review"
                        },
                        style = ArtifactTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }
        }
    }
}
