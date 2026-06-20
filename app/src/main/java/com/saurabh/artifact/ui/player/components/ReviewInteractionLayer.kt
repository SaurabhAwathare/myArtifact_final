package com.saurabh.artifact.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.player.PlayerUiState
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

@Composable
fun ReviewInteractionLayer(
    uiState: PlayerUiState,
    onEditClick: () -> Unit,
    onPublishClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        // 1. Review Progress Card (Adapted from ReviewPlayerScreen)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color.White.copy(alpha = 0.05f),
                    RoundedCornerShape(24.dp)
                )
                .padding(Spacing.Medium)
        ) {
            Text(
                text = "Review Progress",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(bottom = Spacing.Small)
            )

            // Coverage
            ProgressRow(
                label = "Review Progress",
                percent = uiState.coveragePercent,
                color = Color(0xFFFFB74D) // Ember Gold
            )

            Spacer(modifier = Modifier.height(Spacing.Small))

            // Reached End
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reached End",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
                Icon(
                    imageVector = if (uiState.isPlaybackEnded) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (uiState.isPlaybackEnded) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 2. Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onEditClick) {
                Text("Edit", color = Color.White.copy(alpha = 0.7f))
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onPublishClick,
                    enabled = uiState.isThresholdMet,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isThresholdMet) ArtifactTheme.colors.waveformActive else Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.05f),
                        disabledContentColor = Color.White.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Publish")
                }
                
                if (!uiState.isThresholdMet) {
                    val requiredPercent = (uiState.requiredCoverage * 100).toInt()
                    Text(
                        text = "$requiredPercent% review required",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            
            TextButton(onClick = onDeleteClick) {
                Text("Delete", color = Color(0xFFE91E63).copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun ProgressRow(
    label: String,
    percent: Float,
    color: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f)
            )
            Text(
                text = "${(percent * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { percent },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = color,
            trackColor = Color.White.copy(alpha = 0.05f)
        )
    }
}
