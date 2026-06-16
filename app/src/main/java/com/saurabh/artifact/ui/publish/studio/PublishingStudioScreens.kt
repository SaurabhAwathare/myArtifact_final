package com.saurabh.artifact.ui.publish.studio

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.ui.components.EmotionSelector
import com.saurabh.artifact.ui.player.components.PlaybackControls
import com.saurabh.artifact.ui.player.components.PlaybackSpeedSelector
import com.saurabh.artifact.ui.player.components.WaveformScrubber
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.util.TimeUtils
import com.saurabh.artifact.ui.components.moderation.PrivacyNudgeDialog

@Composable
fun PublishingStudioScreen(
    draftId: String,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PublishingStudioViewModel = hiltViewModel()
) {
    val sessionState by viewModel.sessionState.collectAsState()

    LaunchedEffect(draftId) {
        Log.d("LIFECYCLE_TRACE", "PublishingStudioScreen: LaunchedEffect(draftId=$draftId)")
        viewModel.loadDraft(draftId)
    }

    LaunchedEffect(sessionState.isSuccess) {
        if (sessionState.isSuccess && !sessionState.isQueuedOffline) {
            Log.d("NAV_TRACE", "PublishingStudioScreen: sessionState.isSuccess -> onFinish()")
            onFinish()
        }
    }

    BackHandler {
        Log.d("NAV_TRACE", "PublishingStudioScreen: BackHandler triggered. currentStep=${sessionState.currentStep}")
        if (sessionState.currentStep == StudioStep.REVIEW) {
            onCancel()
        } else {
            viewModel.previousStep()
        }
    }

    if (sessionState.showPrivacyNudge) {
        PrivacyNudgeDialog(
            onDismiss = { viewModel.dismissPrivacyNudge() },
            onConfirm = { viewModel.confirmPublishAnyway() },
            leaks = sessionState.privacyWarnings
        )
    }

    Scaffold(
        topBar = {
            StudioTopBar(
                currentStep = sessionState.currentStep,
                onBack = {
                    if (sessionState.currentStep == StudioStep.REVIEW || sessionState.currentStep == StudioStep.PROCESSING) onCancel()
                    else viewModel.previousStep()
                },
                onClose = onCancel
            )
        },
        bottomBar = {
            if (sessionState.currentStep != StudioStep.PUBLISHING && sessionState.currentStep != StudioStep.PROCESSING) {
                StudioBottomBar(
                    state = sessionState,
                    onNext = { viewModel.nextStep() },
                    onPublish = { viewModel.onPublishClick() }
                )
            }
        },
        containerColor = ArtifactTheme.colors.surfaceLoom
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Step Indicator
            StudioProgressIndicator(currentStep = sessionState.currentStep)

            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = sessionState.currentStep,
                    transitionSpec = {
                        if (targetState.index > initialState.index) {
                            slideInHorizontally { it } + fadeIn() togetherWith
                                    slideOutHorizontally { -it } + fadeOut()
                        } else {
                            slideInHorizontally { -it } + fadeIn() togetherWith
                                    slideOutHorizontally { it } + fadeOut()
                        }
                    },
                    label = "StudioStepTransition"
                ) { step ->
                    when (step) {
                        StudioStep.PROCESSING -> StudioLoadingStep()
                        StudioStep.REVIEW -> StudioReviewStep(sessionState, viewModel)
                        StudioStep.DETAILS -> StudioDetailsStep(sessionState, viewModel)
                        StudioStep.APPROVAL -> StudioApprovalStep(sessionState, viewModel)
                        StudioStep.PUBLISHING -> StudioPublishingStep(sessionState, onFinish)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioTopBar(
    currentStep: StudioStep,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = when (currentStep) {
                    StudioStep.PROCESSING -> "Processing..."
                    StudioStep.REVIEW -> "Review Recording"
                    StudioStep.DETAILS -> "Add Details"
                    StudioStep.APPROVAL -> "Ready to Publish?"
                    StudioStep.PUBLISHING -> "Publishing..."
                },
                style = ArtifactTheme.typography.titleMedium
            )
        },
        navigationIcon = {
            if (currentStep != StudioStep.PUBLISHING) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
        },
        actions = {
            if (currentStep != StudioStep.PUBLISHING) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Save & Exit")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = ArtifactTheme.colors.onSurfaceMain
        )
    )
}

@Composable
fun StudioProgressIndicator(currentStep: StudioStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Large, vertical = Spacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        StudioStep.entries.filter { it != StudioStep.PUBLISHING && it != StudioStep.PROCESSING }.forEach { step ->
            val isActive = step.index <= currentStep.index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) ArtifactTheme.colors.waveformActive 
                        else ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.1f)
                    )
            )
        }
    }
}

@Composable
fun StudioBottomBar(
    state: StudioSessionState,
    onNext: () -> Unit,
    onPublish: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
        color = ArtifactTheme.colors.surfaceHearth
    ) {
        Row(
            modifier = Modifier
                .padding(Spacing.Large)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.currentStep == StudioStep.APPROVAL) {
                Button(
                    onClick = onPublish,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = state.reviewCompleted && state.titleCompleted && state.emotionCompleted,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArtifactTheme.colors.waveformActive
                    )
                ) {
                    Text("Publish Artifact", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = when (state.currentStep) {
                        StudioStep.REVIEW -> state.reviewCompleted
                        StudioStep.DETAILS -> state.titleCompleted && state.emotionCompleted
                        else -> true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArtifactTheme.colors.waveformActive
                    )
                ) {
                    Text("Continue", fontWeight = FontWeight.Bold)
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
fun StudioLoadingStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            color = ArtifactTheme.colors.waveformActive
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Securing your reflection...",
            style = ArtifactTheme.typography.titleMedium,
            color = ArtifactTheme.colors.onSurfaceMain
        )
        Text(
            "This will only take a moment.",
            style = ArtifactTheme.typography.bodySmall,
            color = ArtifactTheme.colors.onSurfaceMuted
        )
    }
}

@Composable
fun StudioReviewStep(
    state: StudioSessionState,
    viewModel: PublishingStudioViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Time and Percentage UI
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(state.coveragePercent * 100).toInt()}%",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (state.reviewCompleted) "Review Complete" else "Reviewed",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Seekable Timeline
        val config = LocalConfiguration.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = state.coveragePercent,
                onValueChange = { viewModel.seekTo(it) },
                colors = SliderDefaults.colors(
                    thumbColor = ArtifactTheme.colors.waveformActive,
                    activeTrackColor = ArtifactTheme.colors.waveformActive,
                    inactiveTrackColor = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.1f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = TimeUtils.formatDurationMillis(state.currentPosition, config),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = TimeUtils.formatDurationMillis(state.durationMs, config),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        PlaybackControls(
            isPlaying = state.isPlaying,
            onTogglePlay = { viewModel.togglePlayback() },
            onRewind = { viewModel.seekTo((state.coveragePercent - 0.1f).coerceAtLeast(0f)) },
            onForward = { viewModel.seekTo((state.coveragePercent + 0.1f).coerceAtMost(1f)) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        PlaybackSpeedSelector(
            currentSpeed = state.playbackSpeed,
            onSpeedSelected = { viewModel.setPlaybackSpeed(it) }
        )
    }
}

@Composable
fun StudioDetailsStep(
    state: StudioSessionState,
    viewModel: PublishingStudioViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.Large)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Describe your artifact",
            style = ArtifactTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        OutlinedTextField(
            value = state.title,
            onValueChange = { viewModel.updateTitle(it) },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        EmotionSelector(
            selectedEmotion = state.emotion?.label ?: "",
            onSelect = { label ->
                Emotion.entries.find { it.label == label }?.let {
                    viewModel.updateEmotion(it)
                }
            }
        )
    }
}

@Composable
fun StudioApprovalStep(
    state: StudioSessionState,
    viewModel: PublishingStudioViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.Large),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Final Summary",
            style = ArtifactTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ArtifactTheme.colors.surfaceHearth)
        ) {
            Column(modifier = Modifier.padding(Spacing.Large)) {
                SummaryRow(label = "Title", value = state.title)
                SummaryRow(label = "Emotion", value = state.emotion?.label ?: "None")
                SummaryRow(
                    label = "Review", 
                    value = if (state.reviewCompleted) "Completed" else "${(state.coveragePercent * 100).toInt()}%"
                )
            }
        }

        if (!state.reviewCompleted) {
            Surface(
                color = Color.Yellow.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.Medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Warning, null, tint = Color.Yellow)
                    Spacer(Modifier.width(Spacing.Small))
                    Text(
                        "You must listen to the entire recording before publishing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Yellow
                    )
                }
            }
        }
    }
}

@Composable
fun StudioPublishingStep(
    state: StudioSessionState,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (state.isSuccess) {
            Icon(
                Icons.Rounded.CheckCircle, 
                null, 
                tint = ArtifactTheme.colors.waveformActive,
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text("Published Successfully", style = ArtifactTheme.typography.titleLarge)
            
            if (state.isQueuedOffline) {
                Text(
                    "You're offline. Your artifact will upload automatically.",
                    style = ArtifactTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.Large)
                )
            }
            
            Button(onClick = onFinish, modifier = Modifier.padding(top = 32.dp)) {
                Text("Return to Home")
            }
        } else if (state.error != null) {
            Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(80.dp))
            Text("Publishing Failed", style = ArtifactTheme.typography.titleLarge)
            Text(state.error, color = MaterialTheme.colorScheme.error)
        } else {
            CircularProgressIndicator(modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(24.dp))
            Text("Sending to the world...", style = ArtifactTheme.typography.titleMedium)
        }
    }
}

@Composable
fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.5f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
