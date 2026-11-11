package com.saurabh.artifact.ui.recording.warning

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.saurabh.artifact.R
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.CardShape
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.ManropeFont
import com.saurabh.artifact.ui.theme.MistGray
import com.saurabh.artifact.ui.theme.Obsidian700
import com.saurabh.artifact.ui.theme.Obsidian800
import com.saurabh.artifact.ui.theme.Obsidian900
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.ReflectionWhite
import com.saurabh.artifact.ui.theme.StatusWarning

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
fun PreRecordingWarningScreen(
    onContinue: (String?) -> Unit,
    onCancel: () -> Unit,
    initialPrompt: String? = null,
    viewModel: PreRecordingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var showRationaleDialog by remember { mutableStateOf(false) }
    var showPermanentDenialDialog by remember { mutableStateOf(false) }
    var isStartingRecording by remember { mutableStateOf(false) }
    var hasBegunRecording by remember { mutableStateOf(false) }
    var pendingProceedAfterPermission by remember { mutableStateOf(false) }

    // Re-check permission on app resume (e.g. after returning from Android Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                hasAudioPermission = granted
                if (granted) {
                    showPermanentDenialDialog = false
                    showRationaleDialog = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Handle Bypassing if already recording or preparing in background
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            if (event is PreRecordingWarningEvent.NavigateToRecording) {
                hasBegunRecording = true
                onContinue(initialPrompt)
            }
        }
    }

    var isPermissionPermanentlyDenied by remember { mutableStateOf(false) }

    val executeProceedToRecording = {
        if (!isStartingRecording) {
            isStartingRecording = true
            hasBegunRecording = true
            viewModel.onBeginRecording {
                onContinue(initialPrompt)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { audioGranted ->
        hasAudioPermission = audioGranted
        if (audioGranted) {
            showRationaleDialog = false
            showPermanentDenialDialog = false
            isPermissionPermanentlyDenied = false
            if (pendingProceedAfterPermission) {
                pendingProceedAfterPermission = false
                executeProceedToRecording()
            }
        } else {
            pendingProceedAfterPermission = false
            val permission = Manifest.permission.RECORD_AUDIO
            if (activity?.shouldShowRequestPermissionRationale(permission) == false) {
                showPermanentDenialDialog = true
                isPermissionPermanentlyDenied = true
            }
        }
    }

    val requestPermissions = {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Unified dismiss handler: cancels the ritual and pops back
    val handleDismiss = {
        viewModel.cancel()
        onCancel()
    }

    // System Back support: cancelling ritual cleanly
    BackHandler(enabled = true) {
        handleDismiss()
    }

    // Screen departure guard: ensures ritual is cancelled if dismissed without recording
    DisposableEffect(Unit) {
        onDispose {
            if (!hasBegunRecording) {
                viewModel.cancel()
            }
        }
    }

    // Primary action handler: "Begin Recording"
    val handleBeginRecording = {
        if (!isStartingRecording && !uiState.isStorageCritical) {
            val audioGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            hasAudioPermission = audioGranted

            if (audioGranted) {
                executeProceedToRecording()
            } else {
                val permission = Manifest.permission.RECORD_AUDIO
                if (activity != null && activity.shouldShowRequestPermissionRationale(permission)) {
                    showRationaleDialog = true
                } else if (activity != null && !activity.shouldShowRequestPermissionRationale(permission) && showPermanentDenialDialog) {
                    showPermanentDenialDialog = true
                } else {
                    pendingProceedAfterPermission = true
                    requestPermissions()
                }
            }
        }
    }

    PreRecordingWarningContent(
        uiState = uiState,
        hasAudioPermission = hasAudioPermission,
        isPermissionPermanentlyDenied = isPermissionPermanentlyDenied,
        onBeginRecording = handleBeginRecording,
        onCancel = handleDismiss,
        onCleanUpStorage = { viewModel.openStorageSettings(context) },
        onGrantPermission = {
            if (isPermissionPermanentlyDenied) {
                viewModel.openAppSettings(context)
            } else {
                val permission = Manifest.permission.RECORD_AUDIO
                if (activity != null && activity.shouldShowRequestPermissionRationale(permission)) {
                    showRationaleDialog = true
                } else {
                    pendingProceedAfterPermission = false
                    requestPermissions()
                }
            }
        },
        onOpenAppSettings = { viewModel.openAppSettings(context) },
        isStartingRecording = isStartingRecording
    )

    // Microphone Rationale Dialog
    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            containerColor = Obsidian900,
            title = {
                Text(
                    text = stringResource(R.string.permission_microphone_title),
                    color = ReflectionWhite,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = ManropeFont,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.permission_microphone_body),
                    color = MistGray,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = ManropeFont
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationaleDialog = false
                        requestPermissions()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = GoldAura500)
                ) {
                    Text(
                        text = stringResource(R.string.action_grant_permission),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationaleDialog = false }) {
                    Text(
                        text = "Not Now",
                        color = MistGray
                    )
                }
            }
        )
    }

    // Permanent Denial Dialog
    if (showPermanentDenialDialog) {
        AlertDialog(
            onDismissRequest = { showPermanentDenialDialog = false },
            containerColor = Obsidian900,
            title = {
                Text(
                    text = stringResource(R.string.permission_microphone_permanent_title),
                    color = ReflectionWhite,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = ManropeFont,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.permission_microphone_permanent_body),
                    color = MistGray,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = ManropeFont
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermanentDenialDialog = false
                        viewModel.openAppSettings(context)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = GoldAura500)
                ) {
                    Text(
                        text = stringResource(R.string.action_open_settings),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentDenialDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = MistGray
                    )
                }
            }
        )
    }
}

@Composable
fun PreRecordingWarningContent(
    uiState: PreRecordingWarningUiState,
    hasAudioPermission: Boolean,
    onBeginRecording: () -> Unit,
    onCancel: () -> Unit,
    onCleanUpStorage: () -> Unit,
    onGrantPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
    isStartingRecording: Boolean = false,
    isPermissionPermanentlyDenied: Boolean = false
) {
    // Restrained, subtle GoldAura atmospheric glow (Hearth ambiance, no aggressive red)
    val infiniteTransition = rememberInfiniteTransition(label = "Atmosphere")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Scaffold(
        containerColor = Obsidian950,
        modifier = modifier
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            GoldAura500.copy(alpha = glowAlpha),
                            Obsidian950
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. TOP BAR: Back / Close Button (minimum 48dp touch target)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close_pre_recording),
                            tint = MistGray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. CALM PROTECTIVE ICONOGRAPHY
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = GoldAura500,
                    modifier = Modifier.size(44.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 3. HEADLINE
                Text(
                    text = stringResource(R.string.pre_recording_headline),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = ManropeFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = 32.sp,
                        letterSpacing = (-0.5).sp
                    ),
                    color = ReflectionWhite,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 4. SUBTEXT
                Text(
                    text = stringResource(R.string.pre_recording_subtext),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = ManropeFont
                    ),
                    color = MistGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )

                // 5. STORAGE WARNING BANNER (Preserved 1GB threshold, improved visual treatment)
                if (uiState.isStorageLow) {
                    Spacer(modifier = Modifier.height(20.dp))
                    StorageWarningBanner(
                        isCritical = uiState.isStorageCritical,
                        onCleanUpClick = onCleanUpStorage
                    )
                }

                // 6. MICROPHONE PERMISSION EXPLANATION CARD (If permission is missing)
                if (!hasAudioPermission) {
                    Spacer(modifier = Modifier.height(20.dp))
                    PermissionExplanationCard(
                        isPermanentlyDenied = isPermissionPermanentlyDenied,
                        onGrantPermission = onGrantPermission,
                        onOpenSettings = onOpenAppSettings
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 7. PRIVACY GUIDANCE CARD (Obsidian800, CardShape 16dp, Obsidian700 border)
                Surface(
                    color = Obsidian800,
                    shape = CardShape,
                    border = BorderStroke(1.dp, Obsidian700),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = stringResource(R.string.pre_recording_warning_heading),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = ManropeFont,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp
                            ),
                            color = GoldAura500
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        val sensitiveItems = listOf(
                            stringResource(R.string.pre_recording_warning_item_full_name),
                            stringResource(R.string.pre_recording_warning_item_phone_number),
                            stringResource(R.string.pre_recording_warning_item_address),
                            stringResource(R.string.pre_recording_warning_item_workplace),
                            stringResource(R.string.pre_recording_warning_item_location)
                        )

                        sensitiveItems.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(vertical = 5.dp)
                                    .semantics(mergeDescendants = true) {}
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(GoldAura500.copy(alpha = 0.7f), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = ManropeFont,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    color = ReflectionWhite
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 8. STATIC CALM BREATHING SUGGESTION & EMOTIONAL REASSURANCE (No mandatory timer)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = GoldAura500.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.pre_recording_breathing_suggestion),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = ManropeFont,
                            fontWeight = FontWeight.Normal
                        ),
                        color = MistGray,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 9. DATA DISCLOSURE
                Text(
                    text = stringResource(R.string.audio_collection_disclosure),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = ManropeFont
                    ),
                    color = MistGray.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // 10. PRIMARY ACTION: "Begin Recording"
                // Minimum 56dp height, CardShape 16dp, clear high contrast, min 48dp touch target
                val isButtonEnabled = !uiState.isStorageCritical && !isStartingRecording

                Button(
                    onClick = onBeginRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics {
                            contentDescription = "Begin Recording"
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ReflectionWhite,
                        contentColor = Obsidian950,
                        disabledContainerColor = Obsidian800,
                        disabledContentColor = MistGray.copy(alpha = 0.4f)
                    ),
                    shape = CardShape,
                    enabled = isButtonEnabled
                ) {
                    Text(
                        text = stringResource(R.string.action_begin_recording),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = ManropeFont,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun StorageWarningBanner(
    isCritical: Boolean,
    onCleanUpClick: () -> Unit
) {
    Surface(
        color = Obsidian800,
        shape = CardShape,
        border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = StatusWarning,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isCritical) stringResource(R.string.storage_warning_critical_title) else stringResource(R.string.storage_warning_low_title),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = ManropeFont,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = StatusWarning
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isCritical) stringResource(R.string.storage_warning_critical_body) else stringResource(R.string.storage_warning_low_body),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = ManropeFont
                    ),
                    color = MistGray
                )
            }
            TextButton(
                onClick = onCleanUpClick,
                colors = ButtonDefaults.textButtonColors(contentColor = StatusWarning),
                modifier = Modifier.height(48.dp) // Minimum 48dp touch target
            ) {
                Text(
                    text = stringResource(R.string.action_clean_up),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = ManropeFont
                )
            }
        }
    }
}

@Composable
fun PermissionExplanationCard(
    isPermanentlyDenied: Boolean = false,
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        color = Obsidian800,
        shape = CardShape,
        border = BorderStroke(1.dp, Obsidian700),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = GoldAura500,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPermanentlyDenied) {
                        stringResource(R.string.permission_microphone_permanent_title)
                    } else {
                        stringResource(R.string.permission_microphone_title)
                    },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = ManropeFont,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = ReflectionWhite
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isPermanentlyDenied) {
                        stringResource(R.string.permission_microphone_permanent_body)
                    } else {
                        stringResource(R.string.permission_microphone_body)
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = ManropeFont
                    ),
                    color = MistGray
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = if (isPermanentlyDenied) onOpenSettings else onGrantPermission,
                colors = ButtonDefaults.textButtonColors(contentColor = GoldAura500),
                modifier = Modifier.height(48.dp) // Minimum 48dp touch target
            ) {
                Text(
                    text = if (isPermanentlyDenied) {
                        stringResource(R.string.action_open_settings)
                    } else {
                        stringResource(R.string.action_grant_permission)
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = ManropeFont
                )
            }
        }
    }
}

@Preview
@Composable
fun PreRecordingWarningScreenPreview() {
    ArtifactTheme {
        PreRecordingWarningContent(
            uiState = PreRecordingWarningUiState(
                remainingSeconds = 0,
                isStorageLow = false,
                isStorageCritical = false
            ),
            hasAudioPermission = true,
            onBeginRecording = {},
            onCancel = {},
            onCleanUpStorage = {},
            onGrantPermission = {},
            onOpenAppSettings = {}
        )
    }
}
