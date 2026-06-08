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
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.ui.recording.components.NewRecordingWaveform
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian900
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.util.TimeUtils
import kotlin.math.absoluteValue

@Composable
fun RecordingScreen(
    onFinished: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    var showRationaleDialog by remember { mutableStateOf(false) }
    var showPermanentDenialDialog by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(
        initialPage = uiState.currentPromptIndex,
        pageCount = { uiState.promptList.size }
    )

    // Sync pager state with ViewModel if needed (optional, but good for activePrompt tracking)
    LaunchedEffect(pagerState.currentPage) {
        viewModel.updatePromptIndex(pagerState.currentPage)
    }

    // Sync ViewModel initial state to pager
    LaunchedEffect(uiState.currentPromptIndex) {
        if (uiState.currentPromptIndex != pagerState.currentPage) {
            pagerState.scrollToPage(uiState.currentPromptIndex)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            val permission = Manifest.permission.RECORD_AUDIO
            if (activity?.shouldShowRequestPermissionRationale(permission) == false) {
                showPermanentDenialDialog = true
            }
        }
    }

    val requestPermission = {
        val permission = Manifest.permission.RECORD_AUDIO
        when {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED -> {
                viewModel.startRecording()
            }
            activity?.shouldShowRequestPermissionRationale(permission) == true -> {
                showRationaleDialog = true
            }
            else -> {
                permissionLauncher.launch(permission)
            }
        }
    }

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
                        requestPermission()
                    }
                }
            }
        }
    }

    // Handle Back Press
    BackHandler {
        if (uiState.status == RecordingStatus.RECORDING || uiState.status == RecordingStatus.PAUSED) {
            onBack() // Navigation continues in background
        } else {
            onBack()
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
                        viewModel.cancelRecording()
                        showDeleteConfirmation = false
                        onBack()
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
    LaunchedEffect(uiState.lastDraftId) {
        if (uiState.lastDraftId != null) {
            onFinished(uiState.lastDraftId!!)
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A0808).copy(alpha = glowAlpha),
                            Color(0xFF120909)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // TOP HALF - PROMPT SECTION (Swipeable)
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.promptList.isNotEmpty()) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            pageSpacing = 16.dp
                        ) { page ->
                            val prompt = uiState.promptList[page]
                            val pageOffset by remember {
                                derivedStateOf {
                                    ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                                }
                            }
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        // Cinematic scale and fade
                                        val scale = lerp(
                                            start = 0.85f,
                                            stop = 1f,
                                            fraction = 1f - pageOffset.coerceIn(0f, 1f)
                                        )
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = lerp(
                                            start = 0.3f,
                                            stop = 1f,
                                            fraction = 1f - pageOffset.coerceIn(0f, 1f)
                                        )
                                    }
                                    .blur(radius = (pageOffset * 10).dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // PROMPT LABEL
                                Text(
                                    text = "PROMPT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.2f),
                                    letterSpacing = 2.sp
                                )
                                
                                Spacer(modifier = Modifier.height(24.dp))

                                Text(
                                    text = prompt.question,
                                    style = MaterialTheme.typography.displayMedium.copy(
                                        fontWeight = FontWeight.Light,
                                        fontSize = 32.sp
                                    ),
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 44.sp
                                )
                                
                                Spacer(modifier = Modifier.height(32.dp))

                                // ACTION LINK: Try Another Prompt
                                TextButton(
                                    onClick = { viewModel.nextPrompt() },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = Color(0xFFFFB74D).copy(alpha = 0.6f) // Warm amber
                                    )
                                ) {
                                    Text(
                                        text = "Try Another Prompt →",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Light
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "There’s no pressure to sound perfect.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.4f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        // Fallback/Loading
                        CircularProgressIndicator(color = GoldAura500.copy(alpha = 0.2f))
                    }
                }

                // BOTTOM HALF - RECORDING EXPERIENCE
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Live Waveform (Warm amber energy)
                    NewRecordingWaveform(
                        amplitude = uiState.currentAmplitude,
                        isRecording = uiState.status == RecordingStatus.RECORDING,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    )

                    // Live Timer
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = TimeUtils.formatDuration(uiState.durationSeconds, LocalConfiguration.current),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.ExtraLight,
                                letterSpacing = 4.sp
                            ),
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        
                        uiState.error?.let { error ->
                            val errorMessage = when (error) {
                                is RecordingError.PermissionDenied -> "Permission Denied"
                                is RecordingError.HardwareInUse -> "Microphone in use by another app"
                                is RecordingError.StorageFull -> "Storage is full"
                                is RecordingError.Unknown -> "Recording failed"
                            }
                            Text(
                                text = errorMessage,
                                color = Color(0xFFE91E63),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    // Redesigned Cinematic Controls
                    EnhancedRecordingControls(
                        status = uiState.status,
                        onRecordClick = {
                            if (uiState.status == RecordingStatus.RECORDING || uiState.status == RecordingStatus.PAUSED) {
                                viewModel.stopRecording()
                            } else {
                                requestPermission()
                            }
                        },
                        onPauseClick = {
                            if (uiState.status == RecordingStatus.PAUSED) viewModel.resumeRecording() else viewModel.pauseRecording()
                        },
                        onDeleteClick = {
                            showDeleteConfirmation = true
                        },
                        onFinishClick = {
                            viewModel.stopRecording()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun EnhancedRecordingControls(
    status: RecordingStatus,
    onRecordClick: () -> Unit,
    onPauseClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onFinishClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Delete Button (Low emphasis)
        IconButton(
            onClick = onDeleteClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel",
                tint = Color.White.copy(alpha = 0.3f)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Massive Glowing Mic Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(120.dp)
        ) {
            val isRecording = status == RecordingStatus.RECORDING
            val infiniteTransition = rememberInfiniteTransition(label = "Heartbeat")
            
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (isRecording) 1.15f else 1f,
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

            // Outer Glow
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(pulseScale)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFE91E63).copy(alpha = glowAlpha), // Deep rose/red glow
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            // Inner Button
            Button(
                onClick = onRecordClick,
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color(0xFFE91E63) else Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(0.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop" else "Record",
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Finish/Pause Button (Subtle)
        IconButton(
            onClick = if (status == RecordingStatus.RECORDING) onPauseClick else onFinishClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (status == RecordingStatus.RECORDING) Icons.Default.Pause else Icons.Default.Check,
                contentDescription = "Action",
                tint = Color.White.copy(alpha = if (status == RecordingStatus.IDLE) 0.1f else 0.6f)
            )
        }
    }
}

@Preview
@Composable
fun RecordingScreenPreview() {
    ArtifactTheme {
        Box(modifier = Modifier.background(Obsidian950)) {
            RecordingScreen(
                onFinished = {},
                onBack = {}
            )
        }
    }
}
