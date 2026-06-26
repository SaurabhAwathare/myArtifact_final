package com.saurabh.artifact.ui.comments

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.EngagementStatus

@Composable
fun LockedCommentView(
    progress: Float,
    modifier: Modifier = Modifier,
    requiredCoverage: Float = 0.95f,
    status: EngagementStatus = EngagementStatus.LOCKED
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 500)
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.2f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Rounded.Headset,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Listening creates space for understanding.",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.5.sp
                ),
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val descriptionText = when (status) {
                EngagementStatus.PENDING_VALIDATION -> "Validating..."
                EngagementStatus.FAILED -> "Sync failed. Try listening again."
                else -> "Immersion is required to unlock this conversation."
            }

            Text(
                text = descriptionText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Requirements Checklist
            Column(
                modifier = Modifier
                    .width(240.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                val currentPercent = (animatedProgress * 100).toInt()
                val requiredPercent = (requiredCoverage * 100).toInt()
                
                RequirementCheckItem(
                    label = "Listen to $requiredPercent%",
                    isMet = status == EngagementStatus.UNLOCKED || status == EngagementStatus.PENDING_VALIDATION || animatedProgress >= requiredCoverage,
                    progress = if (status == EngagementStatus.PENDING_VALIDATION) "Synced" else "$currentPercent%"
                )
                
                RequirementCheckItem(
                    label = "Reach end of artifact",
                    isMet = status == EngagementStatus.UNLOCKED || status == EngagementStatus.PENDING_VALIDATION
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun RequirementCheckItem(
    label: String,
    isMet: Boolean,
    progress: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isMet) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isMet) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f),
            modifier = Modifier.size(16.dp)
        )
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isMet) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 8.dp).weight(1f)
        )
        
        if (progress != null) {
            Text(
                text = progress,
                style = MaterialTheme.typography.labelSmall,
                color = if (isMet) Color.White else Color.White.copy(alpha = 0.4f)
            )
        }
    }
}
