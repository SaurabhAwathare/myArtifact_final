package com.saurabh.artifact.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.service.PrivacyAnalysisEngine
import com.saurabh.artifact.service.PrivacyAnalysisResult
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.Obsidian900
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingReviewScreen(
    draftId: String,
    onPublished: () -> Unit,
    onDiscarded: () -> Unit,
    viewModel: RecordingReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(draftId) {
        viewModel.loadDraft(draftId)
    }

    Scaffold(
        containerColor = Obsidian950,
        topBar = {
            TopAppBar(
                title = { Text("Review before publishing", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onDiscarded) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
        ) {
            // Transcript Editor (Placeholder for now)
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Obsidian900),
                shape = RoundedCornerShape(24.dp)
            ) {
                Box(modifier = Modifier.padding(24.dp)) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = GoldAura500)
                    } else {
                        Text(
                            text = uiState.analysisResult?.transcript ?: "Your transcript will appear here...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.8f),
                            lineHeight = 32.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // AI Warning Card
            if (uiState.analysisResult?.detectedRisks?.isNotEmpty() == true) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFB84D).copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Info, null, tint = GoldAura500)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                "This recording may contain personal information.",
                                style = MaterialTheme.typography.labelLarge,
                                color = GoldAura500
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { /* Navigate to full editor */ },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Text("Edit Transcript", color = Color.White)
                }
                
                Button(
                    onClick = { onPublished() },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("Continue Anyway", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onPublished() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GoldAura500, contentColor = Obsidian950),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("Continue to Reflection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@HiltViewModel
class RecordingReviewViewModel @Inject constructor(
    private val privacyAnalysisEngine: PrivacyAnalysisEngine,
    private val recordingRepository: RecordingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingReviewUiState())
    val uiState = _uiState.asStateFlow()

    fun loadDraft(draftId: String) {
        _uiState.update { it.copy(isLoading = true, draftId = draftId) }
        viewModelScope.launch {
            val draft = recordingRepository.getDraft(draftId) ?: return@launch
            val analysisResult = privacyAnalysisEngine.analyze(draft.localAudioPath, draft.localTranscriptPath)
            _uiState.update { it.copy(isLoading = false, analysisResult = analysisResult) }
        }
    }
}

data class RecordingReviewUiState(
    val draftId: String? = null,
    val isLoading: Boolean = false,
    val analysisResult: PrivacyAnalysisResult? = null
)
