package com.saurabh.artifact.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.RecommendationState

/**
 * A non-punitive notice displayed to creators when their artifact is suppressed.
 * Designed to be extensible for future moderation phases (appeals, status updates).
 */
@Composable
fun ModerationBanner(
    recommendationState: RecommendationState,
    modifier: Modifier = Modifier
) {
    if (recommendationState != RecommendationState.SUPPRESSED) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFE65100).copy(alpha = 0.15f))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(20.dp)
            )
            
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Recommendation Update",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFFFB74D),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                
                Text(
                    text = "Your Artifact has been temporarily removed from recommendations while awaiting community moderation review. It remains accessible via direct links.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
                
                // Future Placeholder for Phase 4+ (Appeals, Decisions)
                // Spacer(modifier = Modifier.height(8.dp))
                // Button(...) 
            }
        }
    }
}
