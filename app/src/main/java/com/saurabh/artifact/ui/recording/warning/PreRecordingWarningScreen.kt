package com.saurabh.artifact.ui.recording.warning

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.WarningBackgroundBottom
import com.saurabh.artifact.ui.theme.WarningBackgroundTop
import com.saurabh.artifact.ui.theme.WarningBorder
import com.saurabh.artifact.ui.theme.WarningCard
import com.saurabh.artifact.ui.theme.WarningRed
import com.saurabh.artifact.ui.theme.WarningTextPrimary
import com.saurabh.artifact.ui.theme.WarningTextSecondary

@Composable
fun PreRecordingWarningScreen(
    onContinue: (String?) -> Unit,
    onCancel: () -> Unit,
    initialPrompt: String? = null,
    viewModel: PreRecordingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Handle Bypassing if already active (events from ViewModel)
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            if (event is PreRecordingWarningEvent.NavigateToRecording) {
                onContinue(initialPrompt)
            }
        }
    }

    PreRecordingWarningContent(
        uiState = uiState,
        onContinue = { onContinue(initialPrompt) },
        onCancel = onCancel
    )
}

@Composable
fun PreRecordingWarningContent(
    uiState: PreRecordingWarningUiState,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    // Ambient cinematic glow
    val infiniteTransition = rememberInfiniteTransition(label = "Atmosphere")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Scaffold(
        containerColor = WarningBackgroundBottom
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            WarningBackgroundTop.copy(alpha = alpha * 4),
                            WarningBackgroundBottom
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. TOP BAR: Back Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 2. WARNING ICON
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = WarningRed.copy(alpha = 0.8f),
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 3. HEADLINE
                Text(
                    text = "Before you record...",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Light,
                        fontSize = 38.sp,
                        letterSpacing = (-1).sp
                    ),
                    color = WarningTextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 4. SUBTEXT
                Text(
                    text = "Protect your safety and peace of mind.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = WarningTextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )

                Spacer(modifier = Modifier.height(28.dp))

                // 5. WARNING CARD
                Surface(
                    color = WarningCard,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, WarningBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "Do not mention:",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 22.sp
                            ),
                            color = WarningRed
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        val sensitiveItems = listOf(
                            "your full name",
                            "your mobile number",
                            "addresses",
                            "workplace details",
                            "your location"
                        )
                        
                        sensitiveItems.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(WarningRed.copy(alpha = 0.5f), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Light
                                    ),
                                    color = WarningTextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 6. EMOTIONAL SECTION
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = WarningRed.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Speak honestly. But protect yourself too.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = WarningTextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Light
                )

                Spacer(modifier = Modifier.height(48.dp))

                // 7. COUNTDOWN CIRCLE
                Box(contentAlignment = Alignment.Center) {
                    val isReady = uiState.remainingSeconds == 0
                    
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = EaseInOutSine),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "Pulse"
                    )

                    // Soft radial glow behind timer
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(pulseScale)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        WarningRed.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                    
                    Text(
                        text = if (isReady) "Ready" else uiState.remainingSeconds.toString(),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = if (isReady) 32.sp else 80.sp,
                            fontWeight = FontWeight.ExtraLight,
                            letterSpacing = (-2).sp
                        ),
                        color = if (isReady) WarningRed.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.scale(if (isReady) 1f else pulseScale)
                    )
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                if (uiState.remainingSeconds > 0) {
                    Text(
                        text = "Take a moment",
                        style = MaterialTheme.typography.labelLarge,
                        color = WarningTextSecondary.copy(alpha = 0.2f),
                        letterSpacing = 2.sp
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))

                // 8. I UNDERSTAND BUTTON
                val isReady = uiState.remainingSeconds == 0
                Button(
                    onClick = { onContinue() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReady) Color.White else Color.White.copy(alpha = 0.05f),
                        contentColor = if (isReady) Obsidian950 else Color.White.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = isReady
                ) {
                    Text(
                        text = "I Understand",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 1.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Preview
@Composable
fun PreRecordingWarningScreenPreview() {
    ArtifactTheme {
        PreRecordingWarningContent(
            uiState = PreRecordingWarningUiState(remainingSeconds = 0),
            onContinue = {},
            onCancel = {}
        )
    }
}
