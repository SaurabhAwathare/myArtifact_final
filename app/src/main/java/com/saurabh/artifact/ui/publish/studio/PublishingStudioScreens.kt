package com.saurabh.artifact.ui.publish.studio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.ui.components.EmotionSelector
import com.saurabh.artifact.ui.components.moderation.PrivacyNudgeDialog
import com.saurabh.artifact.ui.player.components.PlaybackControls
import com.saurabh.artifact.ui.player.components.PlaybackSpeedSelector
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.CardShape
import com.saurabh.artifact.ui.theme.ModalShape
import com.saurabh.artifact.ui.theme.Obsidian700
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.ui.theme.TrustMoss
import com.saurabh.artifact.util.TimeUtils

@Composable
fun PublishingStudioScreen(
    draftId: String,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onSecurityRequired: () -> Unit = {},
    viewModel: PublishingStudioViewModel = hiltViewModel()
) {
    val logger = ArtifactTheme.logger
    val sessionState by viewModel.sessionState.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Trigger publish regardless of result to ensure the core feature works,
        // while notifications depend on OS permission grant.
        viewModel.onPublishClick()
    }

    LaunchedEffect(draftId) {
        logger.trace(DiagnosticCategory.STUDIO, "STUDIO_SCREEN_LAUNCHED", mapOf(LogKeys.DRAFT_ID to draftId))
        viewModel.loadDraft(draftId)
    }

    LaunchedEffect(sessionState.isSuccess) {
        if (sessionState.isSuccess) {
            logger.info(DiagnosticCategory.PUBLISH, "STUDIO_SUCCESS_NAVIGATE")
            onFinish()
        }
    }

    LaunchedEffect(sessionState.lifecycle) {
        if (sessionState.lifecycle == ArtifactLifecycle.DELETING) {
            logger.info(DiagnosticCategory.STUDIO, "STUDIO_DRAFT_DELETING_NAVIGATE")
            onCancel()
        }
    }

    LaunchedEffect(sessionState.currentStep) {
        if (sessionState.currentStep == StudioStep.SECURITY_REQUIRED) {
            logger.info(DiagnosticCategory.SECURITY, "STUDIO_SECURITY_REDIRECT")
            onSecurityRequired()
        }
    }

    BackHandler {
        logger.debug(DiagnosticCategory.NAVIGATION, "STUDIO_BACK_TRIGGERED", mapOf("step" to sessionState.currentStep.name))
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = ModalShape,
            containerColor = ArtifactTheme.colors.surfaceHearth,
            title = {
                Text(
                    text = "Delete Draft",
                    style = ArtifactTheme.typography.titleLarge,
                    color = ArtifactTheme.colors.onSurfaceMain,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete this draft? The local recording will be permanently deleted from this device and cannot be recovered.",
                    style = ArtifactTheme.typography.bodyMedium,
                    color = ArtifactTheme.colors.onSurfaceMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDraft()
                        showDeleteConfirm = false
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = ArtifactTheme.colors.actionDestructive)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = ArtifactTheme.colors.onSurfaceMuted)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            StudioTopBar(
                currentStep = sessionState.currentStep,
                isError = sessionState.error != null,
                onBack = {
                    if (sessionState.currentStep == StudioStep.REVIEW || sessionState.currentStep == StudioStep.PROCESSING) onCancel()
                    else viewModel.previousStep()
                },
                onClose = onCancel,
                onDelete = { showDeleteConfirm = true }
            )
        },
        bottomBar = {
            if (sessionState.currentStep != StudioStep.PUBLISHING && sessionState.currentStep != StudioStep.PROCESSING) {
                StudioBottomBar(
                    state = sessionState,
                    onNext = { viewModel.nextStep() },
                    onPublish = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!hasPermission) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.onPublishClick()
                            }
                        } else {
                            viewModel.onPublishClick()
                        }
                    }
                )
            }
        },
        containerColor = ArtifactTheme.colors.surfaceLoom
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            // Publishing Progress Indicator: only displays the 3 user-facing stages
            if (sessionState.currentStep != StudioStep.PROCESSING &&
                sessionState.currentStep != StudioStep.PUBLISHING &&
                sessionState.currentStep != StudioStep.DELETING &&
                sessionState.currentStep != StudioStep.SECURITY_REQUIRED
            ) {
                StudioProgressIndicator(
                    currentStep = sessionState.currentStep
                )
            }

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
                        StudioStep.PROCESSING -> StudioLoadingStep(isRecovering = sessionState.isRecovering)
                        StudioStep.REVIEW -> StudioReviewStep(sessionState, viewModel)
                        StudioStep.DETAILS -> StudioDetailsStep(sessionState, viewModel)
                        StudioStep.APPROVAL -> StudioApprovalStep(sessionState, viewModel)
                        StudioStep.PUBLISHING -> StudioPublishingStep(sessionState, onFinish)
                        StudioStep.DELETING -> { /* Handled by LaunchedEffect */ }
                        StudioStep.SECURITY_REQUIRED -> { /* Handled by LaunchedEffect */ }
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
    isError: Boolean = false,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = when (currentStep) {
                    StudioStep.PROCESSING -> "Processing..."
                    StudioStep.REVIEW -> "Review Recording"
                    StudioStep.DETAILS -> "Add Details"
                    StudioStep.APPROVAL -> "Ready to Publish"
                    StudioStep.PUBLISHING -> if (isError) "Publishing Failed" else "Publishing..."
                    StudioStep.DELETING -> "Deleting..."
                    StudioStep.SECURITY_REQUIRED -> "Security Required"
                },
                style = ArtifactTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        navigationIcon = {
            if ((currentStep != StudioStep.PUBLISHING || isError) && currentStep != StudioStep.DELETING) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = ArtifactTheme.colors.onSurfaceMain
                    )
                }
            }
        },
        actions = {
            if (currentStep != StudioStep.PUBLISHING && currentStep != StudioStep.DELETING) {
                if (currentStep == StudioStep.REVIEW || currentStep == StudioStep.DETAILS || currentStep == StudioStep.APPROVAL) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Delete Draft",
                            tint = ArtifactTheme.colors.actionDestructive
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Save & Exit",
                        tint = ArtifactTheme.colors.onSurfaceMuted
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = ArtifactTheme.colors.onSurfaceMain
        )
    )
}

/**
 * Present the three user-facing stages clearly:
 * 1. Review
 * 2. Details
 * 3. Approval
 * Internal states (PROCESSING, SECURITY_REQUIRED, PUBLISHING) are excluded from user-facing steps.
 */
@Composable
fun StudioProgressIndicator(
    currentStep: StudioStep,
    modifier: Modifier = Modifier
) {
    val stages = listOf(
        StudioStep.REVIEW to "Review",
        StudioStep.DETAILS to "Details",
        StudioStep.APPROVAL to "Approval"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Large, vertical = Spacing.Small)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            stages.forEach { (step, _) ->
                val isCompleted = currentStep.index > step.index
                val isCurrent = currentStep == step
                val isActive = isCompleted || isCurrent

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) ArtifactTheme.colors.goldAura
                            else Obsidian700
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            stages.forEachIndexed { index, (step, label) ->
                val isCurrent = currentStep == step
                val isCompleted = currentStep.index > step.index
                Text(
                    text = "${index + 1}. $label",
                    style = ArtifactTheme.typography.labelSmall,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        isCurrent -> ArtifactTheme.colors.goldAura
                        isCompleted -> ArtifactTheme.colors.onSurfaceMain
                        else -> ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.5f)
                    }
                )
            }
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
        color = ArtifactTheme.colors.surfaceHearth,
        border = BorderStroke(1.dp, Obsidian700)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = Spacing.Large, vertical = Spacing.Medium)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.currentStep == StudioStep.APPROVAL) {
                Button(
                    onClick = onPublish,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = CardShape,
                    enabled = !state.isPublishing && state.reviewCompleted && state.titleCompleted && state.emotionCompleted,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArtifactTheme.colors.goldAura,
                        contentColor = Obsidian950,
                        disabledContainerColor = Obsidian700,
                        disabledContentColor = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
                    )
                ) {
                    if (state.isPublishing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Obsidian950,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Publish Artifact",
                                style = ArtifactTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = CardShape,
                    enabled = when (state.currentStep) {
                        StudioStep.REVIEW -> state.reviewCompleted
                        StudioStep.DETAILS -> state.titleCompleted && state.emotionCompleted
                        StudioStep.PROCESSING,
                        StudioStep.PUBLISHING,
                        StudioStep.DELETING,
                        StudioStep.SECURITY_REQUIRED -> true
                        else -> true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArtifactTheme.colors.goldAura,
                        contentColor = Obsidian950,
                        disabledContainerColor = Obsidian700,
                        disabledContentColor = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Continue",
                            style = ArtifactTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StudioLoadingStep(isRecovering: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(56.dp),
            color = ArtifactTheme.colors.goldAura,
            strokeWidth = 3.dp
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = if (isRecovering) "Resuming processing..." else "Securing your reflection...",
            style = ArtifactTheme.typography.titleMedium,
            color = ArtifactTheme.colors.onSurfaceMain,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isRecovering) "Almost back on track." else "This will only take a moment.",
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
    val config = LocalConfiguration.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Large)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Large)
    ) {
        Spacer(modifier = Modifier.height(Spacing.Small))

        // Episode & Title Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            val displayEpisode = state.episodeNumber ?: state.nextEpisodeNumberPreview
            if (displayEpisode != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ArtifactTheme.colors.goldAura.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ArtifactTheme.colors.goldAura.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "EPISODE $displayEpisode",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = ArtifactTheme.typography.labelSmall,
                        color = ArtifactTheme.colors.goldAura,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                }
            }

            Text(
                text = "Review Recording",
                style = ArtifactTheme.typography.headlineSmall,
                color = ArtifactTheme.colors.onSurfaceMain,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Listen to your recording to ensure acoustic quality and review the full content before publishing.",
                style = ArtifactTheme.typography.bodySmall,
                color = ArtifactTheme.colors.onSurfaceMuted,
                textAlign = TextAlign.Center
            )
        }

        // Section A: Listening Coverage Card (Clear distinction from Playback Position)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = ArtifactTheme.colors.surfaceHearth),
            border = BorderStroke(1.dp, Obsidian700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Headphones,
                            contentDescription = null,
                            tint = ArtifactTheme.colors.goldAura,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Listening Coverage",
                            style = ArtifactTheme.typography.titleSmall,
                            color = ArtifactTheme.colors.onSurfaceMain,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "${(state.coveragePercent * 100).toInt()}%",
                        style = ArtifactTheme.typography.titleLarge,
                        color = if (state.coveragePercent >= 0.95f) TrustMoss else ArtifactTheme.colors.goldAura,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Tracks unique audio reviewed. Rewinding will not reduce coverage.",
                    style = ArtifactTheme.typography.labelSmall,
                    color = ArtifactTheme.colors.onSurfaceMuted
                )

                // Coverage Bar with 95% requirement indicator
                val clampedCoverage = state.coveragePercent.coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(Obsidian700)
                        .semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = clampedCoverage,
                                range = 0f..1f,
                                steps = 0
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(clampedCoverage)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(
                                if (state.coveragePercent >= 0.95f) TrustMoss
                                else ArtifactTheme.colors.goldAura
                            )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "0%",
                        style = ArtifactTheme.typography.labelSmall,
                        color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "95% Required",
                        style = ArtifactTheme.typography.labelSmall,
                        color = if (state.coveragePercent >= 0.95f) TrustMoss else ArtifactTheme.colors.goldAura,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "100%",
                        style = ArtifactTheme.typography.labelSmall,
                        color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.5f)
                    )
                }

                // Requirements feedback
                if (state.reviewCompleted) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = TrustMoss.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, TrustMoss.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.Medium),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = TrustMoss,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Review requirement met. You can now continue.",
                                style = ArtifactTheme.typography.bodySmall,
                                color = ArtifactTheme.colors.onSurfaceMain,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Obsidian950.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, Obsidian700),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.Medium),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = ArtifactTheme.colors.goldAura,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Listen to at least 95% of your recording and reach the end before continuing.",
                                style = ArtifactTheme.typography.bodySmall,
                                color = ArtifactTheme.colors.onSurfaceMuted
                            )
                        }
                    }
                }
            }
        }

        // Section B: Playback Timeline Card (Scrubbing Position)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = ArtifactTheme.colors.surfaceHearth),
            border = BorderStroke(1.dp, Obsidian700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = ArtifactTheme.colors.onSurfaceMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Playback Position",
                            style = ArtifactTheme.typography.titleSmall,
                            color = ArtifactTheme.colors.onSurfaceMain,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = TimeUtils.formatDurationMillis(state.currentPosition, config),
                            style = ArtifactTheme.typography.labelMedium,
                            color = ArtifactTheme.colors.onSurfaceMain,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "/",
                            style = ArtifactTheme.typography.labelMedium,
                            color = ArtifactTheme.colors.onSurfaceMuted
                        )
                        Text(
                            text = TimeUtils.formatDurationMillis(state.durationMs, config),
                            style = ArtifactTheme.typography.labelMedium,
                            color = ArtifactTheme.colors.onSurfaceMuted
                        )
                    }
                }

                val currentPositionText = TimeUtils.formatDurationMillis(state.currentPosition.coerceAtLeast(0L), config)
                val totalDurationText = TimeUtils.formatDurationMillis(state.durationMs.coerceAtLeast(0L), config)

                Slider(
                    value = state.playbackProgress,
                    onValueChange = { viewModel.seekTo(it) },
                    colors = SliderDefaults.colors(
                        thumbColor = ArtifactTheme.colors.goldAura,
                        activeTrackColor = ArtifactTheme.colors.goldAura,
                        inactiveTrackColor = Obsidian700
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics {
                            stateDescription = "$currentPositionText of $totalDurationText"
                        }
                )
            }
        }

        // Section C: Playback Controls & Speed Selector
        PlaybackControls(
            isPlaying = state.isPlaying,
            onTogglePlay = { viewModel.togglePlayback() },
            onRewind = { viewModel.seekTo((state.playbackProgress - 0.1f).coerceAtLeast(0f)) },
            onForward = { viewModel.seekTo((state.playbackProgress + 0.1f).coerceAtMost(1f)) }
        )

        PlaybackSpeedSelector(
            currentSpeed = state.playbackSpeed,
            onSpeedSelected = { viewModel.setPlaybackSpeed(it) }
        )

        Spacer(modifier = Modifier.height(Spacing.Large))
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
            .padding(horizontal = Spacing.Large)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.Large)
    ) {
        Spacer(modifier = Modifier.height(Spacing.Small))

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            Text(
                text = "Describe Your Artifact",
                style = ArtifactTheme.typography.headlineSmall,
                color = ArtifactTheme.colors.onSurfaceMain,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Give your reflection a name and capture its emotional essence.",
                style = ArtifactTheme.typography.bodyMedium,
                color = ArtifactTheme.colors.onSurfaceMuted
            )
        }

        // Title Input Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = ArtifactTheme.colors.surfaceHearth),
            border = BorderStroke(1.dp, Obsidian700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                Text(
                    text = "Artifact Title",
                    style = ArtifactTheme.typography.titleSmall,
                    color = ArtifactTheme.colors.onSurfaceMain,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = state.title,
                    onValueChange = {
                        if (it.length <= 70) {
                            viewModel.updateTitle(it)
                        }
                    },
                    placeholder = {
                        Text(
                            text = "What is this reflection about?",
                            style = ArtifactTheme.typography.bodyLarge,
                            color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.5f)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ArtifactTheme.colors.goldAura,
                        unfocusedBorderColor = Obsidian700,
                        focusedContainerColor = Obsidian950,
                        unfocusedContainerColor = Obsidian950,
                        cursorColor = ArtifactTheme.colors.goldAura,
                        focusedTextColor = ArtifactTheme.colors.onSurfaceMain,
                        unfocusedTextColor = ArtifactTheme.colors.onSurfaceMain
                    ),
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (state.title.isBlank()) "Title is required" else "Concise and reflective",
                                style = ArtifactTheme.typography.labelSmall,
                                color = if (state.title.isBlank()) ArtifactTheme.colors.statusWarning else ArtifactTheme.colors.onSurfaceMuted
                            )
                            Text(
                                text = "${state.title.length}/70",
                                style = ArtifactTheme.typography.labelSmall,
                                color = if (state.title.length == 70) ArtifactTheme.colors.statusWarning else ArtifactTheme.colors.onSurfaceMuted
                            )
                        }
                    },
                    singleLine = false,
                    maxLines = 3
                )
            }
        }

        // Emotion Selection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = ArtifactTheme.colors.surfaceHearth),
            border = BorderStroke(1.dp, Obsidian700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                val selectedEmotionLabels = state.effectiveEmotions.map { it.label }
                EmotionSelector(
                    selectedEmotions = selectedEmotionLabels,
                    onEmotionToggle = { label ->
                        val targetEmotion = Emotion.entries.find {
                            it.label.equals(label, ignoreCase = true) || it.name.equals(label, ignoreCase = true)
                        }
                        if (targetEmotion != null) {
                            val currentEmotions = state.effectiveEmotions.toMutableList()
                            if (currentEmotions.contains(targetEmotion)) {
                                currentEmotions.remove(targetEmotion)
                            } else if (currentEmotions.size < 3) {
                                currentEmotions.add(targetEmotion)
                            }
                            viewModel.updateEmotions(currentEmotions)
                        }
                    }
                )

                if (!state.emotionCompleted) {
                    Text(
                        text = "Select at least 1 emotion to continue.",
                        style = ArtifactTheme.typography.labelSmall,
                        color = ArtifactTheme.colors.statusWarning
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.Large))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioApprovalStep(
    state: StudioSessionState,
    viewModel: PublishingStudioViewModel
) {
    val config = LocalConfiguration.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Large)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.Large)
    ) {
        Spacer(modifier = Modifier.height(Spacing.Small))

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            val displayEpisode = state.episodeNumber ?: state.nextEpisodeNumberPreview
            if (displayEpisode != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ArtifactTheme.colors.goldAura.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ArtifactTheme.colors.goldAura.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "EPISODE $displayEpisode",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = ArtifactTheme.typography.labelSmall,
                        color = ArtifactTheme.colors.goldAura,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                }
            }

            Text(
                text = "Ready to Publish",
                style = ArtifactTheme.typography.headlineSmall,
                color = ArtifactTheme.colors.onSurfaceMain,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Confirm your artifact details before publishing. Your reflection will be encrypted and saved to your anonymous profile.",
                style = ArtifactTheme.typography.bodyMedium,
                color = ArtifactTheme.colors.onSurfaceMuted
            )
        }

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = ArtifactTheme.colors.surfaceHearth),
            border = BorderStroke(1.dp, Obsidian700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                SummaryRow(
                    label = "Title",
                    value = state.title.ifBlank { "Untitled Artifact" }
                )

                HorizontalDivider(color = Obsidian700, thickness = 1.dp)

                // Emotions display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "Emotions",
                        modifier = Modifier.widthIn(min = 80.dp),
                        style = ArtifactTheme.typography.labelMedium,
                        color = ArtifactTheme.colors.onSurfaceMuted
                    )
                    if (state.effectiveEmotions.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            state.effectiveEmotions.forEach { emotion ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Obsidian950,
                                    border = BorderStroke(1.dp, Obsidian700)
                                ) {
                                    Text(
                                        text = "${emotion.emoji} ${emotion.label}",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = ArtifactTheme.typography.labelSmall,
                                        color = ArtifactTheme.colors.onSurfaceMain
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "None",
                            style = ArtifactTheme.typography.bodyMedium,
                            color = ArtifactTheme.colors.onSurfaceMuted
                        )
                    }
                }

                HorizontalDivider(color = Obsidian700, thickness = 1.dp)

                SummaryRow(
                    label = "Duration",
                    value = TimeUtils.formatDurationMillis(state.durationMs, config)
                )

                HorizontalDivider(color = Obsidian700, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Review Status",
                        modifier = Modifier.widthIn(min = 80.dp),
                        style = ArtifactTheme.typography.labelMedium,
                        color = ArtifactTheme.colors.onSurfaceMuted
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (state.reviewCompleted) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                            contentDescription = null,
                            tint = if (state.reviewCompleted) TrustMoss else ArtifactTheme.colors.goldAura,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.reviewCompleted) "Verified" else "${(state.coveragePercent * 100).toInt()}% reviewed",
                            style = ArtifactTheme.typography.bodyMedium,
                            color = if (state.reviewCompleted) TrustMoss else ArtifactTheme.colors.goldAura,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Privacy & Identity Protection Notice
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = ArtifactTheme.colors.surfaceHearth),
            border = BorderStroke(1.dp, Obsidian700)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.Large),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = ArtifactTheme.colors.goldAura,
                    modifier = Modifier.size(24.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Anonymous & Protected",
                        style = ArtifactTheme.typography.titleSmall,
                        color = ArtifactTheme.colors.onSurfaceMain,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Published under your anonymous persona. Your audio is encrypted on your device and protected by encryption at rest in cloud storage. It is not end-to-end encrypted.",
                        style = ArtifactTheme.typography.bodySmall,
                        color = ArtifactTheme.colors.onSurfaceMuted
                    )
                }
            }
        }

        // Warnings and Errors
        if (!state.reviewSatisfied) {
            Surface(
                color = ArtifactTheme.colors.statusWarning.copy(alpha = 0.12f),
                shape = CardShape,
                border = BorderStroke(1.dp, ArtifactTheme.colors.statusWarning.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.Medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = ArtifactTheme.colors.statusWarning,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Spacing.Small))
                    Text(
                        text = "You must listen to at least 95% of your recording and reach the end before publishing.",
                        style = ArtifactTheme.typography.bodySmall,
                        color = ArtifactTheme.colors.statusWarning
                    )
                }
            }
        }

        if (state.error != null) {
            Surface(
                color = ArtifactTheme.colors.statusError.copy(alpha = 0.12f),
                shape = CardShape,
                border = BorderStroke(1.dp, ArtifactTheme.colors.statusError.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.Medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = ArtifactTheme.colors.statusError,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Spacing.Small))
                    Text(
                        text = state.error,
                        style = ArtifactTheme.typography.bodyMedium,
                        color = ArtifactTheme.colors.statusError
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.Large))
    }
}

@Composable
fun StudioPublishingStep(
    state: StudioSessionState,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (state.isSuccess) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = TrustMoss,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Published Successfully",
                style = ArtifactTheme.typography.headlineMedium,
                color = ArtifactTheme.colors.onSurfaceMain,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            if (state.isQueuedOffline) {
                Surface(
                    shape = CardShape,
                    color = ArtifactTheme.colors.surfaceHearth,
                    border = BorderStroke(1.dp, Obsidian700),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.Large)
                ) {
                    Text(
                        text = "You're offline. Your artifact has been safely encrypted and queued to upload automatically when you reconnect.",
                        style = ArtifactTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = ArtifactTheme.colors.onSurfaceMuted,
                        modifier = Modifier.padding(Spacing.Large)
                    )
                }
            } else {
                Text(
                    text = "Your reflection has been safely shared with the community.",
                    style = ArtifactTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = ArtifactTheme.colors.onSurfaceMuted
                )
            }

            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ArtifactTheme.colors.goldAura,
                    contentColor = Obsidian950
                )
            ) {
                Text(
                    text = "Return to Home",
                    style = ArtifactTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        } else if (state.error != null) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = ArtifactTheme.colors.statusError,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Publishing Failed",
                style = ArtifactTheme.typography.headlineMedium,
                color = ArtifactTheme.colors.statusError,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.error,
                style = ArtifactTheme.typography.bodyMedium,
                color = ArtifactTheme.colors.onSurfaceMuted,
                textAlign = TextAlign.Center
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                color = ArtifactTheme.colors.goldAura,
                strokeWidth = 3.dp
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (state.isProcessingActive) "Finalizing audio optimization for upload..." else "Publishing reflection...",
                style = ArtifactTheme.typography.titleMedium,
                color = ArtifactTheme.colors.onSurfaceMain,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Encrypting and syncing with your anonymous profile.",
                style = ArtifactTheme.typography.bodySmall,
                color = ArtifactTheme.colors.onSurfaceMuted
            )
        }
    }
}

@Composable
fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.widthIn(min = 80.dp),
            style = ArtifactTheme.typography.labelMedium,
            color = ArtifactTheme.colors.onSurfaceMuted
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = ArtifactTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = ArtifactTheme.colors.onSurfaceMain
        )
    }
}
