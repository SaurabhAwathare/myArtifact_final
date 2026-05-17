package com.saurabh.artifact.ui.drafts

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.ui.components.EmotionSelector
import com.saurabh.artifact.ui.components.ResonanceSettingsSheet
import com.saurabh.artifact.model.ReactionVisibilityMode
import com.saurabh.artifact.ui.player.components.PlaybackControls
import com.saurabh.artifact.ui.player.components.WaveformScrubber
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.Obsidian950
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftEditScreen(
    draftFilePath: String,
    onBack: () -> Unit,
    onPublished: () -> Unit,
    viewModel: DraftEditViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showResonanceSheet by remember { mutableStateOf(false) }

    LaunchedEffect(draftFilePath) {
        viewModel.loadDraft(draftFilePath)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is DraftEditEvent.Published -> onPublished()
                is DraftEditEvent.Deleted -> onBack()
                is DraftEditEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        containerColor = Obsidian950,
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isListenedEnough) "Finalize Artifact" else "Private Review") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A0808),
                            Obsidian950
                        )
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. THE LISTENING ROOM (Player Section)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                shape = RoundedCornerShape(32.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Take a moment to listen back.",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Light
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Waveform Review
                    WaveformScrubber(
                        amplitudes = uiState.draft?.amplitudeData ?: emptyList(),
                        progress = uiState.playbackProgress,
                        isPaused = !uiState.isPlaying,
                        onSeek = { viewModel.seekTo(it) },
                        modifier = Modifier.fillMaxWidth().height(80.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    PlaybackControls(
                        isPlaying = uiState.isPlaying,
                        onTogglePlay = { viewModel.togglePlayPause() },
                        onRewind = { viewModel.seekTo((uiState.playbackProgress - 0.1f).coerceAtLeast(0f)) },
                        onForward = { viewModel.seekTo((uiState.playbackProgress + 0.1f).coerceAtMost(1f)) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Progress Indicator
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { uiState.reviewedPercentage },
                            modifier = Modifier.size(48.dp),
                            color = if (uiState.isListenedEnough) GoldAura400 else Color(0xFFFFB74D).copy(alpha = 0.4f),
                            strokeWidth = 2.dp,
                            trackColor = Color.White.copy(alpha = 0.05f)
                        )
                        if (uiState.isListenedEnough) {
                            Icon(Icons.Rounded.Check, null, tint = GoldAura400, modifier = Modifier.size(20.dp))
                        } else {
                            Text(
                                "${(uiState.reviewedPercentage * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }

            // 2. REFLECTION GUIDANCE
            if (!uiState.isListenedEnough) {
                Column(
                    modifier = Modifier.padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = Color(0xFFFFB74D).copy(alpha = 0.4f),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Listen to your artifact before publishing. This is a private space for reflection.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }

            // 3. THE REFLECTION STAGE (Edit Section - Revealed after listening)
            AnimatedVisibility(
                visible = uiState.isListenedEnough,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Divider(color = Color.White.copy(alpha = 0.05f))
                    
                    Text(
                        "Reflect & Define",
                        style = MaterialTheme.typography.headlineSmall,
                        color = GoldAura400,
                        fontWeight = FontWeight.Light
                    )

                    OutlinedTextField(
                        value = uiState.title,
                        onValueChange = viewModel::updateTitle,
                        label = { Text("What did you learn?") },
                        placeholder = { Text("Title your reflection...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldAura400,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedLabelColor = GoldAura400,
                            unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                        ),
                        isError = uiState.title.isBlank(),
                        supportingText = {
                            if (uiState.title.isBlank()) {
                                Text("Add a title to continue")
                            }
                        }
                    )

                    Text(
                        "How are you feeling now?",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )

                    EmotionSelector(
                        selectedEmotion = uiState.selectedEmotion,
                        onSelect = viewModel::updateEmotion
                    )

                    if (uiState.selectedEmotion.isBlank()) {
                        Text(
                            text = "Choose how this artifact feels",
                            color = Color(0xFFFFB84D),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp, start = 8.dp)
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Show support from listeners?",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showResonanceSheet = true }
                                    .padding(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when(uiState.reactionVisibility) {
                                            ReactionVisibilityMode.VISIBLE -> Icons.Rounded.Visibility
                                            ReactionVisibilityMode.APPROXIMATE -> Icons.Rounded.Waves
                                            ReactionVisibilityMode.CREATOR_ONLY -> Icons.Rounded.Person
                                            ReactionVisibilityMode.HIDDEN -> Icons.Rounded.VisibilityOff
                                        },
                                        contentDescription = null,
                                        tint = GoldAura400,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = when(uiState.reactionVisibility) {
                                                ReactionVisibilityMode.VISIBLE -> "Show support count"
                                                ReactionVisibilityMode.APPROXIMATE -> "Show support count" // Simplified per feedback
                                                ReactionVisibilityMode.CREATOR_ONLY -> "Keep support private"
                                                ReactionVisibilityMode.HIDDEN -> "Keep support private"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White
                                        )
                                    }
                                    Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, modifier = Modifier.size(12.dp), tint = Color.White.copy(alpha = 0.2f))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = viewModel::publish,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        shape = RoundedCornerShape(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GoldAura400,
                            contentColor = Obsidian950,
                            disabledContainerColor = GoldAura400.copy(alpha = 0.3f),
                            disabledContentColor = Obsidian950.copy(alpha = 0.5f)
                        ),
                        enabled = !uiState.isPublishing && uiState.canPublish
                    ) {
                        if (uiState.isPublishing) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Obsidian950)
                        } else {
                            Text("Publish Reflection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    TextButton(
                        onClick = { viewModel.deleteDraft() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, null, tint = Color.White.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete Draft", color = Color.White.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }

    if (showResonanceSheet) {
        ResonanceSettingsSheet(
            currentMode = uiState.reactionVisibility,
            onModeSelected = viewModel::updateReactionVisibility,
            onDismiss = { showResonanceSheet = false }
        )
    }

    if (uiState.isPublishing || uiState.draftState == com.saurabh.artifact.model.ArtifactDraftState.UPLOADING || uiState.draftState == com.saurabh.artifact.model.ArtifactDraftState.PUBLISHED) {
        PublishingOverlay(
            state = uiState.draftState,
            uploadedBytes = uiState.uploadedBytes,
            totalBytes = uiState.totalBytes,
            onClose = {
                if (uiState.draftState == com.saurabh.artifact.model.ArtifactDraftState.PUBLISHED) {
                    onPublished()
                }
            }
        )
    }
}

@Composable
fun PublishingOverlay(
    state: com.saurabh.artifact.model.ArtifactDraftState,
    uploadedBytes: Long,
    totalBytes: Long,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable(enabled = state == com.saurabh.artifact.model.ArtifactDraftState.PUBLISHED) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            val progress = if (totalBytes > 0) uploadedBytes.toFloat() / totalBytes else 0f
            
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                CircularProgressIndicator(
                    progress = { if (state == com.saurabh.artifact.model.ArtifactDraftState.PUBLISHED) 1f else progress },
                    modifier = Modifier.fillMaxSize(),
                    color = GoldAura400,
                    strokeWidth = 4.dp,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
                
                if (state == com.saurabh.artifact.model.ArtifactDraftState.PUBLISHED) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = GoldAura400,
                        modifier = Modifier.size(80.dp)
                    )
                } else {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = when (state) {
                    com.saurabh.artifact.model.ArtifactDraftState.PUBLISHED -> "Your artifact is now part of the archive."
                    com.saurabh.artifact.model.ArtifactDraftState.UPLOADING -> "Publishing your artifact..."
                    else -> "Preparing your artifact..."
                },
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (state) {
                    com.saurabh.artifact.model.ArtifactDraftState.PUBLISHED -> "Tap anywhere to continue"
                    com.saurabh.artifact.model.ArtifactDraftState.UPLOADING -> "Uploading audio and securing transcript"
                    else -> "Finalizing metadata"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}
