package com.saurabh.artifact.ui.recording

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950

@Composable
fun PostRecordingDecisionScreen(
    onReview: (String) -> Unit,
    onSaveToDrafts: () -> Unit,
    viewModel: PostRecordingDecisionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Prevent accidental back-press dismissal. 
    // The user MUST choose an action to proceed.
    BackHandler {
        // Optional: Show a "Save to Drafts before leaving?" snackbar/dialog 
        // if the user tries to exit without choosing.
        // For now, we ground them here to ensure intentionality.
    }

    val infiniteTransition = rememberInfiniteTransition(label = "Glow")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian950)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF2D1B1B).copy(alpha = pulseAlpha),
                        Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Calm Visual Anchor (Subtle pulse)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .alpha(0.3f)
                    .background(GoldAura500.copy(alpha = 0.1f), CircleShape)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxSize(),
                    color = GoldAura500,
                    strokeWidth = 1.dp
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Your artifact is saved locally",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp
                ),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "You can review it now or continue later from your drafts. Nothing is lost.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                lineHeight = 28.sp
            )

            Spacer(modifier = Modifier.height(64.dp))

            // Primary Action: Review
            Button(
                onClick = { uiState.draftId?.let { onReview(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldAura500,
                    contentColor = Obsidian950
                ),
                shape = RoundedCornerShape(32.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Text(
                    text = "Review Artifact",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Secondary Action: Save to Drafts (Equally respected)
            OutlinedButton(
                onClick = onSaveToDrafts,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.15f)
                ),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White.copy(alpha = 0.7f)
                )
            ) {
                Text(
                    text = "Save to Drafts",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

// Re-using CircleShape if not imported from elsewhere, or defining local
private val CircleShape = RoundedCornerShape(50)
