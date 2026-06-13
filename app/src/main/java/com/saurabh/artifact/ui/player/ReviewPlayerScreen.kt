package com.saurabh.artifact.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.ui.components.AmbientWaveform
import com.saurabh.artifact.ui.components.WaveformContext
import com.saurabh.artifact.ui.player.components.TranscriptSyncView
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing
import androidx.compose.ui.tooling.preview.Preview
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A dedicated, private screen for reviewing draft artifacts.
 * STRICTLY NO PUBLISHING UI. Only playback and reflection controls.
 */
@Composable
fun ReviewPlayerScreen(
    draftId: String,
    onReviewComplete: () -> Unit,
    onClose: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val reviewState by viewModel.reviewState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val transcriptSegments by viewModel.transcriptSegments.collectAsStateWithLifecycle()

    var showTranscript by remember { mutableStateOf(false) }

    // Gesture State for swipe-down to collapse
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(draftId) {
        viewModel.startReview(draftId)
    }

    // Automatic transition logic
    LaunchedEffect(reviewState.isThresholdMet) {
        if (reviewState.isThresholdMet) {
            onReviewComplete()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        // Only allow downward drag
                        offsetY = (offsetY + dragAmount).coerceAtLeast(0f)
                    },
                    onDragEnd = {
                        if (offsetY > 300f) {
                            onClose()
                        }
                        offsetY = 0f
                    },
                    onDragCancel = {
                        offsetY = 0f
                    }
                )
            },
        color = ArtifactTheme.colors.surfaceLoom
    ) {
        ReviewPlayerContent(
            draft = draft,
            reviewState = reviewState,
            playbackState = playbackState,
            transcriptSegments = transcriptSegments,
            showTranscript = showTranscript,
            onToggleTranscript = { showTranscript = !showTranscript },
            onClose = onClose,
            onTogglePlayback = { viewModel.togglePlayback() },
            onRewind = { viewModel.rewind() },
            onForward = { viewModel.forward() },
            onSetPlaybackSpeed = { viewModel.setPlaybackSpeed(it) },
            onSeekToPosition = { viewModel.seekToPosition(it) }
        )
    }
}

@Composable
private fun ProgressRow(
    label: String,
    percent: Float,
    color: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = ArtifactTheme.typography.labelSmall,
                color = ArtifactTheme.colors.onSurfaceMuted
            )
            Text(
                text = "${(percent * 100).toInt()}%",
                style = ArtifactTheme.typography.labelSmall,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { percent },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            color = color,
            trackColor = ArtifactTheme.colors.waveformInactive.copy(alpha = 0.2f)
        )
    }
}

private fun formatTimestamp(ts: Long, locale: Locale): String {
    return SimpleDateFormat("MMM d, yyyy", locale).format(Date(ts))
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun ReviewPlayerScreenPreview() {
    ArtifactTheme {
        ReviewPlayerContent(
            draft = ArtifactDraftEntity(
                id = "preview_id",
                localAudioPath = "",
                title = "Midnight Reflections",
                createdAt = 1717666200000L, // June 6, 2024
                durationMs = 60000,
                amplitudeData = listOf(0.5f, 0.4f, 0.6f, 0.8f, 0.5f, 0.4f, 0.6f, 0.8f)
            ),
            reviewState = com.saurabh.artifact.audio.ReviewState(
                coveragePercent = 0.45f,
                effortPercent = 0.62f,
                isPlaybackEnded = false
            ),
            playbackState = PlaybackUiState(
                isPlaying = true,
                currentPositionMs = 27000,
                playbackSpeed = 1.0f
            ),
            onClose = {},
            onTogglePlayback = {},
            onRewind = {},
            onForward = {},
            onSetPlaybackSpeed = {},
            transcriptSegments = emptyList(),
            showTranscript = false,
            onToggleTranscript = {},
            onSeekToPosition = {}
        )
    }
}

@Composable
private fun ReviewPlayerContent(
    draft: ArtifactDraftEntity?,
    reviewState: com.saurabh.artifact.audio.ReviewState,
    playbackState: PlaybackUiState,
    transcriptSegments: List<com.saurabh.artifact.model.TranscriptSegment>,
    showTranscript: Boolean,
    onToggleTranscript: () -> Unit,
    onClose: () -> Unit,
    onTogglePlayback: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSeekToPosition: (Long) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ArtifactTheme.colors.surfaceLoom
    ) {
        if (draft == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ArtifactTheme.colors.waveformActive)
            }
        } else {
            val configuration = LocalConfiguration.current
            val locale = configuration.locales[0]
            val progressPercent = if (draft.durationMs > 0) {
                playbackState.currentPositionMs.toFloat() / draft.durationMs
            } else 0f

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(Spacing.Large),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = ArtifactTheme.colors.onSurfaceMuted)
                    }

                    Text(
                        text = "Private Review Session",
                        style = ArtifactTheme.typography.labelMedium,
                        color = ArtifactTheme.colors.onSurfaceMuted
                    )

                    IconButton(onClick = onToggleTranscript) {
                        Icon(
                            imageVector = if (showTranscript) Icons.Rounded.GraphicEq else Icons.Rounded.Description,
                            contentDescription = if (showTranscript) "Show Visualization" else "Show Transcript",
                            tint = if (showTranscript) ArtifactTheme.colors.waveformActive else ArtifactTheme.colors.onSurfaceMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(0.1f))

                // Central Content Space (Visualization or Transcript)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = showTranscript,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
                        },
                        label = "ContentTransition"
                    ) { isShowingTranscript ->
                        if (isShowingTranscript) {
                            TranscriptSyncView(
                                segments = transcriptSegments,
                                currentPosition = playbackState.currentPositionMs,
                                onSegmentClick = onSeekToPosition,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(CircleShape)
                                    .background(ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(120.dp),
                                    tint = ArtifactTheme.colors.waveformActive.copy(alpha = 0.1f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.ExtraLarge))

                // Metadata Preview (ReadOnly)
                Text(
                    text = draft.title ?: "Untitled Reflection",
                    style = ArtifactTheme.typography.headlineSmall,
                    color = ArtifactTheme.colors.onSurfaceMain,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Draft created on ${formatTimestamp(draft.createdAt, locale)}",
                    style = ArtifactTheme.typography.labelSmall,
                    color = ArtifactTheme.colors.onSurfaceMuted
                )

                Spacer(modifier = Modifier.weight(1f))

                // Review Progress Indicator
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Medium)
                        .background(
                            ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.3f),
                            MaterialTheme.shapes.medium
                        )
                        .padding(Spacing.Medium)
                ) {
                    Text(
                        text = "Review Progress",
                        style = ArtifactTheme.typography.labelSmall,
                        color = ArtifactTheme.colors.onSurfaceMuted,
                        modifier = Modifier.padding(bottom = Spacing.Small)
                    )

                    // 1. Coverage
                    ProgressRow(
                        label = "Coverage",
                        percent = reviewState.coveragePercent,
                        color = ArtifactTheme.colors.waveformActive
                    )

                    Spacer(modifier = Modifier.height(Spacing.Small))

                    // 2. Effort
                    ProgressRow(
                        label = "Effort",
                        percent = reviewState.effortPercent,
                        color = Color(0xFF64B5F6) // Soft Blue
                    )

                    Spacer(modifier = Modifier.height(Spacing.Small))

                    // 3. Reached End
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reached End",
                            style = ArtifactTheme.typography.labelSmall,
                            color = ArtifactTheme.colors.onSurfaceMuted
                        )
                        Icon(
                            imageVector = if (reviewState.isPlaybackEnded) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (reviewState.isPlaybackEnded) ArtifactTheme.colors.waveformActive else ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.3f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.Large))

                // Playback Speed Controls
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Medium),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        val isSelected = playbackState.playbackSpeed == speed
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSetPlaybackSpeed(speed) },
                            label = {
                                Text(
                                    text = "${if ((speed % 1) == 0f) speed.toInt() else speed}x",
                                    style = ArtifactTheme.typography.labelSmall
                                )
                            },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArtifactTheme.colors.waveformActive,
                                selectedLabelColor = Color.White,
                                containerColor = Color.Transparent,
                                labelColor = ArtifactTheme.colors.onSurfaceMuted
                            ),
                            border = if (!isSelected) FilterChipDefaults.filterChipBorder(
                                borderColor = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.2f),
                                borderWidth = 1.dp,
                                enabled = true,
                                selected = false
                            ) else null,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.Small))

                // Waveform & Controls
                AmbientWaveform(
                    amplitudes = draft.amplitudeData.ifEmpty { listOf(0.5f, 0.4f, 0.6f, 0.8f) },
                    progress = progressPercent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    isPaused = !playbackState.isPlaying,
                    context = WaveformContext.Player
                )

                Spacer(modifier = Modifier.height(Spacing.Large))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onRewind) {
                        Icon(Icons.Rounded.Replay10, null, modifier = Modifier.size(32.dp))
                    }

                    FilledIconButton(
                        onClick = onTogglePlayback,
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = ArtifactTheme.colors.waveformActive
                        )
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Toggle Playback",
                            modifier = Modifier.size(40.dp),
                            tint = Color.White
                        )
                    }

                    IconButton(onClick = onForward) {
                        Icon(Icons.Rounded.Forward30, null, modifier = Modifier.size(32.dp))
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.ExtraLarge))
            }
        }
    }
}
