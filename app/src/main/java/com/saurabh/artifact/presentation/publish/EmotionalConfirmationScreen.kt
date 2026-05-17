package com.saurabh.artifact.presentation.publish

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.presentation.publish.components.*

@Composable
fun EmotionalConfirmationScreen(
    viewModel: PublishFlowViewModel,
    onNavigateBack: () -> Unit,
    onPublished: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    
    // Deep warm background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)) // Near black for comfort
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = uiState.ritualState) {
                is PublishRitualState.Processing -> {
                    ProcessingStageView()
                }
                is PublishRitualState.Reviewing -> {
                    TranscriptReviewStageView(state.transcriptJson) {
                        viewModel.moveToState(PublishRitualState.PrivacyCheck(uiState.flaggedSegments))
                    }
                }
                is PublishRitualState.PrivacyCheck -> {
                    PrivacyCheckStageView(state.findings) {
                        viewModel.moveToState(PublishRitualState.FinalConfirmation)
                    }
                }
                is PublishRitualState.FinalConfirmation -> {
                    FinalRitualStageView(
                        progress = uiState.holdToPublishProgress,
                        onProgress = { viewModel.onHoldProgressChanged(it) },
                        onComplete = { viewModel.confirmPublish() }
                    )
                }
                else -> {
                    // Default fallback to the original confirmation logic
                    DefaultConfirmationView(uiState, viewModel, onNavigateBack)
                }
            }
        }
    }
}

@Composable
fun FinalRitualStageView(
    progress: Float,
    onProgress: (Float) -> Unit,
    onComplete: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(60.dp))
        Text(
            text = "Final Release",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "When you're ready, hold the button to share your artifact with the world.",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(80.dp))
        HoldToPublishButton(
            onProgress = onProgress,
            onComplete = onComplete
        )
    }
}

@Composable
fun ProcessingStageView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(100.dp))
        ReflectionPauseAnimation()
        Spacer(modifier = Modifier.height(32.dp))
        Text("Holding your words...", color = Color.White)
    }
}

@Composable
fun TranscriptReviewStageView(
    transcript: String,
    onContinue: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = "Review your words",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.05f)
        ) {
            Box(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = transcript.ifBlank { "No transcript available." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f),
                    lineHeight = 28.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(40.dp))
        
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Looks correct")
        }
    }
}

@Composable
fun PrivacyCheckStageView(
    findings: List<com.saurabh.artifact.model.FlaggedSegment>,
    onContinue: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = "Privacy Guard",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "We've detected some personal details. Would you like to keep them or redact them before sharing?",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (findings.isEmpty()) {
            Text("No sensitive info detected. You're safe to go!", color = Color.White.copy(alpha = 0.6f))
        } else {
            findings.forEach { finding ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(finding.type.name, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFD580))
                            Text(finding.originalText, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                        }
                        Switch(checked = true, onCheckedChange = {}) // Mock toggle
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(40.dp))
        
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Continue")
        }
    }
}

@Composable
fun DefaultConfirmationView(
    uiState: EmotionalConfirmationUiState,
    viewModel: PublishFlowViewModel,
    onNavigateBack: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(40.dp))
        
        Text(
            text = "Take a moment before sharing",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Your reflection will become visible to others anonymously.\n\nMake sure you're comfortable with:\n• the emotions shared\n• the details included\n• the experience becoming public",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        ReflectionPauseAnimation()
        
        Spacer(modifier = Modifier.height(48.dp))
        
        EmotionalRiskBanner(riskAssessment = uiState.riskAssessment)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "How do you feel about sharing this?",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        var selectedOption by remember { mutableStateOf<Int?>(null) }
        
        ConfirmationOptionCard(
            title = "I feel comfortable sharing",
            isSelected = selectedOption == 0,
            onClick = { selectedOption = 0 }
        )
        Spacer(modifier = Modifier.height(12.dp))
        ConfirmationOptionCard(
            title = "Save privately for now",
            isSelected = selectedOption == 1,
            onClick = { 
                selectedOption = 1
                viewModel.savePrivately()
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        ConfirmationOptionCard(
            title = "I'm unsure",
            isSelected = selectedOption == 2,
            onClick = { selectedOption = 2 }
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        if (uiState.isCooldownActive) {
            Text(
                text = "Cooldown active: ${uiState.cooldownRemainingSeconds}s",
                color = Color(0xFFFFBF00),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            PublishActionFooter(
                onPublishClick = { viewModel.confirmPublish() },
                onReviewClick = onNavigateBack,
                isPublishEnabled = selectedOption == 0 && !uiState.isProcessing
            )
        }
    }
}
