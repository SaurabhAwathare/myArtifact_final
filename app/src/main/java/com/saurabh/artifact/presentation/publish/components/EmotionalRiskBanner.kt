package com.saurabh.artifact.presentation.publish.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import com.saurabh.artifact.model.EmotionalRiskAssessment

@Composable
fun EmotionalRiskBanner(
    riskAssessment: EmotionalRiskAssessment?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = riskAssessment?.isHighIntensity == true,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFFBF00).copy(alpha = 0.15f))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFFFFBF00)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "This reflection sounds emotionally intense.",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFFFBF00),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You may want to:\n• review sensitive details\n• wait before publishing\n• save privately first",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFBF00).copy(alpha = 0.9f)
            )
        }
    }
}
