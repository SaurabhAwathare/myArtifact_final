package com.saurabh.artifact.ui.recording

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.ui.components.AmbientWaveform
import com.saurabh.artifact.ui.components.WaveformContext
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian800
import com.saurabh.artifact.ui.theme.Obsidian900
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.util.TimeUtils

@Composable
fun RecordingScreen(
    onFinished: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    var previousStatus by remember { mutableStateOf<RecordingStatus?>(null) }
    LaunchedEffect(uiState.status) {
        val prev = previousStatus
        previousStatus = uiState.status
        @Suppress("DEPRECATION")
        if (prev != null && prev != uiState.status && !view.isInEditMode) {
            when {
                prev == RecordingStatus.IDLE && uiState.status == RecordingStatus.RECORDING -> {
                    view.announceForAccessibility("Recording started")
                }
                prev == RecordingStatus.RECORDING && uiState.status == RecordingStatus.PAUSED -> {
                    view.announceForAccessibility("Recording paused")
                }
                prev == RecordingStatus.PAUSED && uiState.status == RecordingStatus.RECORDING -> {
                    view.announceForAccessibility("Recording resumed")
                }
            }
        }
    }

    var showRationaleDialog by remember { mutableStateOf(false) }
    var showPermanentDenialDialog by remember { mutableStateOf(false) }

    // Best practice: Use rememberUpdatedState for callbacks used in side effects
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnBack by rememberUpdatedState(onBack)
    val safeOnBack = {
        if (uiState.status != RecordingStatus.PREPARING) {
            currentOnBack()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { audioGranted ->
        if (audioGranted) {
            viewModel.startRecording()
        } else {
            val permission = Manifest.permission.RECORD_AUDIO
            if (activity?.shouldShowRequestPermissionRationale(permission) == false) {
                showPermanentDenialDialog = true
            }
        }
    }

    val requestPermission = {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    
    val currentRequestPermission by rememberUpdatedState(requestPermission)

    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            containerColor = Obsidian900,
            title = {
                Text(
                    "Microphone Access",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    "To record your reflection and create an Artifact, we need access to your microphone. Your audio is processed locally and stays private.",
                    color = Color.White.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationaleDialog = false
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = GoldAura500)
                ) {
                    Text("Allow Access")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationaleDialog = false }) {
                    Text("Not Now", color = Color.White.copy(alpha = 0.5f))
                }
            }
        )
    }

    if (showPermanentDenialDialog) {
        AlertDialog(
            onDismissRequest = { showPermanentDenialDialog = false },
            containerColor = Obsidian900,
            title = {
                Text(
                    "Permission Required",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    "It looks like microphone permissions are permanently disabled. Please enable them in Settings to record your Artifacts.",
                    color = Color.White.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermanentDenialDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = GoldAura500)
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentDenialDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.5f))
                }
            }
        )
    }

    // Handle Start Event
    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordingEvent.RequestStart -> {
                    if (uiState.status == RecordingStatus.IDLE || uiState.status == RecordingStatus.FAILED) {
                        currentRequestPermission()
                    }
                }
            }
        }
    }

    // Handle Back Press
    BackHandler(enabled = true) {
        if (uiState.status == RecordingStatus.PREPARING) {
            // Block system back navigation during recording finalization
            return@BackHandler
        }
        if (uiState.status == RecordingStatus.RECORDING || uiState.status == RecordingStatus.PAUSED) {
            currentOnBack() // Navigation continues in background
        } else {
            currentOnBack()
        }
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = Color(0xFF1A0808),
            title = {
                Text(
                    "Delete this recording?",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    "This artifact will be permanently removed. This action cannot be undone.",
                    color = Color.White.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (uiState.status != RecordingStatus.PREPARING) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.cancelRecording()
                            showDeleteConfirmation = false
                            currentOnBack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE91E63))
                ) {
                    Text("Delete Recording")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.5f))
                }
            }
        )
    }

    // Navigation when finished (Navigates to Decision Screen)
    LaunchedEffect(viewModel.navigationEvents) {
        viewModel.navigationEvents.collect { draftId ->
            currentOnFinished(draftId)
        }
    }

    // Ambient background animation
    val infiniteTransition = rememberInfiniteTransition(label = "Atmosphere")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Scaffold(
        containerColor = Obsidian950
    ) { innerPadding ->
        RecordingScreenContent(
            uiState = uiState,
            innerPadding = innerPadding,
            glowAlpha = glowAlpha,
            onNextPrompt = { viewModel.nextPrompt() },
            onPrimaryActionClick = { currentRequestPermission() },
            onPauseClick = { viewModel.pauseRecording() },
            onResumeClick = { viewModel.resumeRecording() },
            onDeleteClick = { showDeleteConfirmation = true },
            onFinishClick = { viewModel.stopRecording() },
            onRetryClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.startRecording()
            },
            onExitClick = safeOnBack
        )
    }
}

@Composable
fun RecordingScreenContent(
    uiState: RecordingUiState,
    innerPadding: PaddingValues = PaddingValues(0.dp),
    glowAlpha: Float = 0.1f,
    onNextPrompt: () -> Unit = {},
    onPrimaryActionClick: () -> Unit = {},
    onPauseClick: () -> Unit = {},
    onResumeClick: () -> Unit = onPauseClick,
    onDeleteClick: () -> Unit = {},
    onFinishClick: () -> Unit = {},
    onRetryClick: () -> Unit = onPrimaryActionClick,
    onExitClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A0808).copy(alpha = glowAlpha),
                        Color(0xFF120909)
                    )
                )
            )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val screenWidth = maxWidth
            val screenHeight = maxHeight

            val isLandscape = screenWidth > screenHeight && screenHeight < 500.dp
            val isCompactWidth = screenWidth < 360.dp
            val isMediumWidth = screenWidth in 360.dp..400.dp

            val isShortHeight = screenHeight < 600.dp
            val isMediumHeight = screenHeight in 600.dp..750.dp

            // Adaptive layout tokens
            val headerHorizontalPadding = when {
                isCompactWidth -> 16.dp
                isMediumWidth -> 20.dp
                else -> 24.dp
            }
            val headerTopPadding = when {
                isShortHeight -> 10.dp
                isMediumHeight -> 12.dp
                else -> 16.dp
            }
            val headerBottomPadding = when {
                isShortHeight -> 4.dp
                else -> 8.dp
            }

            val promptHorizontalPadding = when {
                isCompactWidth -> 20.dp
                isMediumWidth -> 28.dp
                else -> 40.dp
            }
            val promptFontSize = when {
                isShortHeight || isCompactWidth -> 24.sp
                isMediumHeight -> 28.sp
                else -> 32.sp
            }
            val promptLineHeight = when {
                isShortHeight || isCompactWidth -> 32.sp
                isMediumHeight -> 38.sp
                else -> 44.sp
            }
            val promptSpacerSmall = when {
                isShortHeight -> 8.dp
                isMediumHeight -> 12.dp
                else -> 16.dp
            }
            val promptSpacerMedium = when {
                isShortHeight -> 14.dp
                isMediumHeight -> 22.dp
                else -> 28.dp
            }
            val subtitleStyle = if (isShortHeight || isCompactWidth) {
                MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
            } else {
                MaterialTheme.typography.bodyLarge
            }

            val adaptiveWaveformHeight = when {
                isShortHeight -> 52.dp
                isMediumHeight -> 68.dp
                else -> 80.dp
            }
            val waveformHorizontalPadding = when {
                isCompactWidth -> 16.dp
                else -> 24.dp
            }
            val waveformToTimerSpacer = when {
                isShortHeight -> 6.dp
                isMediumHeight -> 10.dp
                else -> 16.dp
            }

            val timerFontSize = when {
                isShortHeight -> 28.sp
                isMediumHeight -> 32.sp
                else -> 36.sp
            }
            val timerLetterSpacing = when {
                isShortHeight -> 2.sp
                else -> 4.sp
            }
            val timerToControlsSpacer = when {
                isShortHeight -> 10.dp
                isMediumHeight -> 18.dp
                else -> 32.dp
            }
            val bottomSectionPadding = when {
                isShortHeight -> 12.dp
                isMediumHeight -> 20.dp
                else -> 40.dp
            }

            val isCompactControls = isShortHeight || isCompactWidth

            val displayEpisode = uiState.episodeNumber ?: uiState.nextEpisodeNumberPreview

            if (!isLandscape) {
                // =========================================================================
                // PORTRAIT LAYOUT: Integrated Header, Flexible Prompt, Pinned Controls
                // =========================================================================
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // TOP: EPISODE HEADER (Integrated into vertical hierarchy — eliminates REC-1 overlap)
                    if (displayEpisode != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .padding(
                                    start = headerHorizontalPadding,
                                    top = headerTopPadding,
                                    bottom = headerBottomPadding
                                )
                        ) {
                            Text(
                                text = "Artifact · Episode $displayEpisode",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Normal,
                                    letterSpacing = 1.2.sp
                                ),
                                color = GoldAura500,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // MIDDLE: PROMPT SECTION (Flexible weight, adaptive typography & margins, scrollable fallback)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = uiState.currentPrompt,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(500)) togetherWith
                                fadeOut(animationSpec = tween(500))
                            },
                            label = "PromptSwitch"
                        ) { prompt ->
                            if (prompt != null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = promptHorizontalPadding)
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    // CATEGORY LABEL
                                    Text(
                                        text = prompt.category.displayLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GoldAura500.copy(alpha = 0.7f),
                                        letterSpacing = 2.sp
                                    )

                                    Spacer(modifier = Modifier.height(promptSpacerSmall))

                                    // REFLECTION QUESTION
                                    Text(
                                        text = prompt.question,
                                        style = MaterialTheme.typography.displayMedium.copy(
                                            fontWeight = FontWeight.Light,
                                            fontSize = promptFontSize
                                        ),
                                        color = Color.White,
                                        textAlign = TextAlign.Start,
                                        lineHeight = promptLineHeight
                                    )

                                    Spacer(modifier = Modifier.height(promptSpacerMedium))

                                    // ACTION LINK: Try Another Prompt
                                    if (!uiState.isPromptLoading) {
                                        TextButton(
                                            onClick = onNextPrompt,
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = Color(0xFFFFB74D).copy(alpha = 0.6f)
                                            ),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(
                                                text = "Try Another Prompt →",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Light
                                            )
                                        }
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = GoldAura500.copy(alpha = 0.4f),
                                            strokeWidth = 2.dp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(promptSpacerSmall))

                                    Text(
                                        text = "There’s no pressure to sound perfect.",
                                        style = subtitleStyle,
                                        color = Color.White.copy(alpha = 0.4f),
                                        textAlign = TextAlign.Start
                                    )
                                }
                            } else {
                                CircularProgressIndicator(color = GoldAura500.copy(alpha = 0.2f))
                            }
                        }
                    }

                    // BOTTOM: RECORDING EXPERIENCE (Fixed to natural content height, remains accessible)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = bottomSectionPadding),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Live Waveform (Resolves REC-4: explicit adaptive height, no conflicting weight)
                        AmbientWaveform(
                            amplitudes = uiState.amplitudes,
                            liveAmplitude = uiState.currentAmplitude,
                            progress = 0f,
                            isPaused = (uiState.status == RecordingStatus.PAUSED),
                            samplingMode = com.saurabh.artifact.util.WaveformProcessor.SamplingMode.SCROLL,
                            context = WaveformContext.Recording,
                            waveformHeight = adaptiveWaveformHeight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = waveformHorizontalPadding)
                        )

                        Spacer(modifier = Modifier.height(waveformToTimerSpacer))

                        // Live Timer
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val timerDescription = buildString {
                                append("Recording duration: ")
                                val mins = uiState.durationSeconds / 60
                                val secs = uiState.durationSeconds % 60
                                if (mins > 0) {
                                    append("$mins ${if (mins == 1L) "minute" else "minutes"} ")
                                }
                                append("$secs ${if (secs == 1L) "second" else "seconds"}")
                            }
                            Text(
                                text = TimeUtils.formatDuration(uiState.durationSeconds, LocalConfiguration.current),
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.ExtraLight,
                                    fontSize = timerFontSize,
                                    letterSpacing = timerLetterSpacing
                                ),
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.semantics {
                                    contentDescription = timerDescription
                                }
                            )

                            if (uiState.isStorageLow && uiState.error == null) {
                                Text(
                                    text = "Low storage space",
                                    color = Color(0xFFFFB74D),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            uiState.error?.let { error ->
                                val errorMessage = when (error) {
                                    is RecordingError.PermissionDenied -> "Permission Denied"
                                    is RecordingError.HardwareInUse -> "Microphone in use by another app"
                                    is RecordingError.StorageFull -> "Storage is full"
                                    is RecordingError.ReservationFailed -> "Network connection required to assign Episode Number before recording"
                                    is RecordingError.Unknown -> "Recording failed"
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text(
                                        text = errorMessage,
                                        color = Color(0xFFE91E63),
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        TextButton(
                                            onClick = onRetryClick,
                                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.6f))
                                        ) {
                                            Text("Retry")
                                        }

                                        TextButton(
                                            onClick = onExitClick,
                                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.4f))
                                        ) {
                                            Text("Exit")
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(timerToControlsSpacer))

                        // Cinematic Recording Controls (Resolves REC-3)
                        EnhancedRecordingControls(
                            status = uiState.status,
                            compact = isCompactControls,
                            onPrimaryActionClick = onPrimaryActionClick,
                            onPauseClick = onPauseClick,
                            onResumeClick = onResumeClick,
                            onDeleteClick = onDeleteClick,
                            onFinishClick = onFinishClick
                        )
                    }
                }
            } else {
                // =========================================================================
                // LANDSCAPE LAYOUT: Side-by-Side Prompt & Recording Controls
                // =========================================================================
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LEFT COLUMN: Header + Prompt
                    Column(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(end = 12.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (displayEpisode != null) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(bottom = 6.dp)
                            ) {
                                Text(
                                    text = "Artifact · Episode $displayEpisode",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Normal,
                                        letterSpacing = 1.sp
                                    ),
                                    color = GoldAura500,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        uiState.currentPrompt?.let { prompt ->
                            Text(
                                text = prompt.category.displayLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = GoldAura500.copy(alpha = 0.7f),
                                letterSpacing = 2.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = prompt.question,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontWeight = FontWeight.Light,
                                    fontSize = 20.sp
                                ),
                                color = Color.White,
                                textAlign = TextAlign.Start,
                                lineHeight = 26.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            if (!uiState.isPromptLoading) {
                                TextButton(
                                    onClick = onNextPrompt,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = Color(0xFFFFB74D).copy(alpha = 0.6f)
                                    ),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = "Try Another Prompt →",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Light
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // RIGHT COLUMN: Waveform + Timer + Controls
                    Column(
                        modifier = Modifier
                            .weight(0.9f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AmbientWaveform(
                            amplitudes = uiState.amplitudes,
                            liveAmplitude = uiState.currentAmplitude,
                            progress = 0f,
                            isPaused = (uiState.status == RecordingStatus.PAUSED),
                            samplingMode = com.saurabh.artifact.util.WaveformProcessor.SamplingMode.SCROLL,
                            context = WaveformContext.Recording,
                            waveformHeight = 44.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        )

                        val timerDescription = buildString {
                            append("Recording duration: ")
                            val mins = uiState.durationSeconds / 60
                            val secs = uiState.durationSeconds % 60
                            if (mins > 0) append("$mins min ")
                            append("$secs sec")
                        }
                        Text(
                            text = TimeUtils.formatDuration(uiState.durationSeconds, LocalConfiguration.current),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.ExtraLight,
                                fontSize = 24.sp,
                                letterSpacing = 2.sp
                            ),
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.semantics { contentDescription = timerDescription }
                        )

                        EnhancedRecordingControls(
                            status = uiState.status,
                            compact = true,
                            onPrimaryActionClick = onPrimaryActionClick,
                            onPauseClick = onPauseClick,
                            onResumeClick = onResumeClick,
                            onDeleteClick = onDeleteClick,
                            onFinishClick = onFinishClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedRecordingControls(
    status: RecordingStatus,
    compact: Boolean = false,
    onPrimaryActionClick: () -> Unit = {},
    onPauseClick: () -> Unit = {},
    onResumeClick: () -> Unit = onPauseClick,
    onDeleteClick: () -> Unit = {},
    onFinishClick: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current

    val isRecording = status == RecordingStatus.RECORDING
    val isPaused = status == RecordingStatus.PAUSED
    val isPreparing = status == RecordingStatus.PREPARING

    // Controls enabled logic:
    // When PREPARING, all three controls must be disabled.
    // Left (Discard) & Right (Finish) are enabled only during active recording or paused recording.
    // Center is enabled during active recording, paused recording, or idle/failed (to start).
    val discardEnabled = !isPreparing && (isRecording || isPaused)
    val finishEnabled = !isPreparing && (isRecording || isPaused)
    val centerEnabled = !isPreparing && (isRecording || isPaused || status == RecordingStatus.IDLE || status == RecordingStatus.FAILED)

    val centerBoxSize = if (compact) 96.dp else 120.dp
    val outerGlowSize = if (compact) 84.dp else 100.dp
    val innerButtonSize = if (compact) 72.dp else 80.dp
    val centerIconSize = if (compact) 28.dp else 32.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 16.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Left Control — Discard (Minimum 48dp x 48dp touch target)
        IconButton(
            onClick = {
                if (discardEnabled) {
                    onDeleteClick()
                }
            },
            enabled = discardEnabled,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Discard Recording",
                tint = if (discardEnabled) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.12f)
            )
        }

        // Center Control — Glowing Pause / Resume / Mic Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(centerBoxSize)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "Heartbeat")

            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (isRecording) (if (compact) 1.10f else 1.15f) else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Pulse"
            )

            val glowAlpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = if (isRecording) 0.6f else 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Glow"
            )

            val glowColor = if (isPaused) {
                ArtifactTheme.colors.recordingPaused
            } else {
                ArtifactTheme.colors.recordingActive
            }

            // Outer Glow
            if (isRecording || isPaused) {
                Box(
                    modifier = Modifier
                        .size(outerGlowSize)
                        .scale(pulseScale)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    glowColor.copy(alpha = if (isPaused) glowAlpha * 0.5f else glowAlpha),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )
            }

            // Inner Button
            Button(
                onClick = {
                    if (centerEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        when (status) {
                            RecordingStatus.RECORDING -> onPauseClick()
                            RecordingStatus.PAUSED -> onResumeClick()
                            else -> onPrimaryActionClick()
                        }
                    }
                },
                enabled = centerEnabled,
                modifier = Modifier.size(innerButtonSize),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isRecording -> ArtifactTheme.colors.recordingActive
                        isPaused -> ArtifactTheme.colors.recordingPaused
                        else -> Color.White.copy(alpha = 0.1f)
                    },
                    contentColor = Color.White,
                    disabledContainerColor = when {
                        isRecording || isPaused || isPreparing -> ArtifactTheme.colors.recordingPaused.copy(alpha = 0.38f)
                        else -> Color.White.copy(alpha = 0.05f)
                    },
                    disabledContentColor = Color.White.copy(alpha = 0.38f)
                ),
                contentPadding = PaddingValues(0.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Icon(
                    imageVector = when {
                        isRecording -> Icons.Default.Pause
                        isPaused -> Icons.Default.PlayArrow
                        isPreparing -> Icons.Default.Pause
                        else -> Icons.Default.Mic
                    },
                    contentDescription = when {
                        isRecording -> "Pause Recording"
                        isPaused -> "Resume Recording"
                        isPreparing -> "Pause Recording"
                        else -> "Start Recording"
                    },
                    modifier = Modifier.size(centerIconSize)
                )
            }
        }

        // Right Control — Finish (Minimum 48dp x 48dp touch target)
        IconButton(
            onClick = {
                if (finishEnabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onFinishClick()
                }
            },
            enabled = finishEnabled,
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = if (finishEnabled) Obsidian800 else Obsidian800.copy(alpha = 0.38f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Finish Recording",
                tint = if (finishEnabled) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.38f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Preview
@Composable
fun RecordingScreenPreview() {
    ArtifactTheme {
        Box(modifier = Modifier.background(Obsidian950)) {
            RecordingScreenContent(
                uiState = RecordingUiState(
                    episodeNumber = 12,
                    currentPrompt = com.saurabh.artifact.model.ReflectionPrompt(
                        id = "preview_prompt",
                        category = com.saurabh.artifact.model.PromptCategory.GENERAL,
                        question = "What is a moment that changed the way you see yourself?"
                    ),
                    status = RecordingStatus.RECORDING,
                    durationSeconds = 85L,
                    amplitudes = List(30) { (it % 8) / 10f },
                    currentAmplitude = 0.5f
                )
            )
        }
    }
}
