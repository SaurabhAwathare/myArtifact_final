package com.saurabh.artifact.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.R
import com.saurabh.artifact.auth.CredentialResult
import com.saurabh.artifact.ui.components.AppSnackbarHost
import com.saurabh.artifact.ui.components.NotificationRationaleDialog
import com.saurabh.artifact.ui.theme.ModalShape
import com.saurabh.artifact.ui.util.LocalBottomOverlayOffset
import com.saurabh.artifact.ui.util.UiText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onLogoutSuccess: () -> Unit,
    onNavigateToIdentity: () -> Unit = {},
    onCustomizeAvatar: () -> Unit = onNavigateToIdentity,
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToSigilRitual: (() -> Unit)? = null
) {
    val handleAvatarClick = onNavigateToSigilRitual ?: onCustomizeAvatar
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val anonymousIdentity by viewModel.anonymousIdentity.collectAsStateWithLifecycle()
    val accountInfo by viewModel.accountInfo.collectAsStateWithLifecycle()
    val isAnonymous by viewModel.isAnonymous.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val deletionConfirmationInput by viewModel.deletionConfirmationInput.collectAsStateWithLifecycle()
    val isDeletionConfirmed by viewModel.isDeletionConfirmed.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showReauthenticationDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    var showExportConfirmation by remember { mutableStateOf(false) }
    var showNotificationRationale by remember { mutableStateOf(false) }
    var pendingActionAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val serverClientId = stringResource(R.string.default_web_client_id)
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingActionAfterPermission?.invoke()
        }
        pendingActionAfterPermission = null
    }

    val handleNotificationToggle: (Boolean, (Boolean) -> Unit) -> Unit = { enabled, updateFn ->
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                pendingActionAfterPermission = { updateFn(true) }
                val activity = context as? FragmentActivity
                if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                    showNotificationRationale = true
                } else {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                updateFn(true)
            }
        } else {
            updateFn(enabled)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri ->
            uri?.let { viewModel.exportData(context, it) }
        }
    )

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is SettingsUiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message.asString(context))
                }
                is SettingsUiEvent.AccountDeleted -> {
                    snackbarHostState.showSnackbar(UiText.StringResource(R.string.account_deleted).asString(context))
                    kotlinx.coroutines.delay(1.seconds) // Brief delay to show snackbar
                    onLogoutSuccess()
                }
                is SettingsUiEvent.LoggedOut -> {
                    onLogoutSuccess()
                }
                is SettingsUiEvent.ReauthenticationRequired -> {
                    showReauthenticationDialog = true
                }
                is SettingsUiEvent.ExportStarted -> {
                    snackbarHostState.showSnackbar(
                        UiText.StringResource(R.string.export_started_feedback).asString(context)
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Section 1 — Identity & Account
                SettingsSection(title = "Identity & Account") {
                    SettingsIdentityItem(
                        state = anonymousIdentity,
                        onEditIdentity = onNavigateToIdentity,
                        onCustomizeAvatar = handleAvatarClick,
                        onEditSigil = onNavigateToSigilRitual
                    )

                    accountInfo?.let { info ->
                        SettingsInfoItem(
                            title = info.realName.toUnsecureString(),
                            subtitle = info.email.toUnsecureString(),
                            icon = Icons.Rounded.Person,
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "Private to you",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { viewModel.copyEmailToClipboard(context) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = "Copy email",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        )
                    }
                }

                // Section 2 — Notifications & Rituals
                SettingsSection(title = "Notifications & Rituals") {
                    SettingsSwitch(
                        title = "Notifications",
                        subtitle = "Stay connected with reflections",
                        icon = Icons.Rounded.Notifications,
                        checked = uiState.notificationsEnabled,
                        onCheckedChange = { enabled ->
                            handleNotificationToggle(enabled) { viewModel.updateNotifications(it) }
                        }
                    )
                    SettingsSwitch(
                        title = "Smart Reminders",
                        subtitle = "Gentle nudges for your ritual",
                        icon = Icons.Rounded.Schedule,
                        checked = uiState.smartRemindersEnabled,
                        onCheckedChange = { enabled ->
                            handleNotificationToggle(enabled) { viewModel.updateSmartReminders(it) }
                        }
                    )
                }

                // Section 3 — Privacy & Safety
                SettingsSection(title = "Privacy & Safety") {
                    SettingsSwitch(
                        title = "Stealth Mode",
                        subtitle = "Hide application content from screenshots and system previews",
                        icon = Icons.Rounded.Shield,
                        checked = uiState.stealthModeEnabled,
                        onCheckedChange = { viewModel.updateStealthMode(it) }
                    )
                    SettingsSwitch(
                        title = "Personalize My Feed",
                        subtitle = "Improve recommendations based on your listening behavior. Interaction data stays on your device.",
                        icon = Icons.Rounded.Tune,
                        checked = uiState.dataCollectionConsent,
                        onCheckedChange = { viewModel.updateDataCollection(it) }
                    )
                }

                // Section 4 — Data Management
                SettingsSection(title = "Data Management") {
                    SettingsClickable(
                        title = "Export Data",
                        subtitle = if (isExporting) 
                            "Export currently in progress. Track in notifications." 
                        else 
                            "Export your personal reflections and recordings as a portable archive",
                        icon = Icons.Rounded.Download,
                        enabled = !isExporting,
                        onClick = { showExportConfirmation = true }
                    )
                    SettingsClickable(
                        title = "Delete Account",
                        subtitle = "Permanently remove your traces",
                        icon = Icons.Rounded.DeleteForever,
                        textColor = MaterialTheme.colorScheme.error,
                        onClick = { showDeleteConfirmation = true }
                    )
                }

                // Section 5 — Account & Session
                SettingsSection(title = "Account & Session") {
                    SettingsClickable(
                        title = "Logout",
                        subtitle = "Sign out of your session on this device",
                        icon = Icons.AutoMirrored.Rounded.Logout,
                        onClick = { showLogoutConfirmation = true }
                    )
                }

                // Section 6 — Legal
                SettingsSection(title = "Legal", showDivider = false) {
                    val uriHandler = LocalUriHandler.current
                    val privacyUrl = stringResource(R.string.privacy_policy_url)
                    val termsUrl = stringResource(R.string.terms_of_service_url)

                    SettingsClickable(
                        title = stringResource(R.string.privacy_policy),
                        subtitle = "How we protect your presence",
                        icon = Icons.Rounded.Shield,
                        trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                        onClick = {
                            try {
                                uriHandler.openUri(privacyUrl)
                            } catch (_: Exception) {
                            }
                        }
                    )
                    SettingsClickable(
                        title = stringResource(R.string.terms_of_service),
                        subtitle = "Guidelines for our sanctuary",
                        icon = Icons.Rounded.Gavel,
                        trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                        onClick = {
                            try {
                                uriHandler.openUri(termsUrl)
                            } catch (_: Exception) {
                            }
                        }
                    )
                }

                // Clearance for active floating overlays (MiniPlayer / MiniRecorder)
                val bottomOverlayOffset = LocalBottomOverlayOffset.current
                if (bottomOverlayOffset > 0.dp) {
                    Spacer(modifier = Modifier.height(bottomOverlayOffset))
                }
            }
            
            if (isDeleting) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { 
                showDeleteConfirmation = false 
                viewModel.updateDeletionConfirmation("")
            },
            shape = ModalShape,
            title = { Text("Delete Account permanently?") },
            text = { 
                Column {
                    Text(
                        stringResource(R.string.account_deletion_warning),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.account_deletion_export_recommendation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = deletionConfirmationInput,
                        onValueChange = { viewModel.updateDeletionConfirmation(it) },
                        label = { Text("Type 'DELETE' to confirm") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = deletionConfirmationInput.isNotEmpty() && !isDeletionConfirmed
                    )
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            showDeleteConfirmation = false
                            exportLauncher.launch("artifact_export_${System.currentTimeMillis()}.zip")
                        }
                    ) {
                        Text("Export My Data First")
                    }
                    TextButton(
                        onClick = {
                            showDeleteConfirmation = false
                            viewModel.initiateDelete()
                        },
                        enabled = isDeletionConfirmed
                    ) {
                        Text("Delete Permanently", color = if (isDeletionConfirmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showDeleteConfirmation = false 
                        viewModel.updateDeletionConfirmation("")
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showReauthenticationDialog) {
        AlertDialog(
            onDismissRequest = { /* Non-dismissible: deletion has begun and must complete */ },
            shape = ModalShape,
            title = { Text("Verify your identity") },
            text = { 
                Text(
                    if (isAnonymous) "To delete your anonymous account, we need to verify your current session."
                    else "For your security, please sign in again before permanently deleting your account."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isAnonymous) {
                            showReauthenticationDialog = false
                            viewModel.reauthenticateAndRetry()
                        } else {
                            coroutineScope.launch {
                                val result = viewModel.credentialHelper.getGoogleCredential(
                                    context = context,
                                    serverClientId = serverClientId,
                                    filterByAuthorizedAccounts = false
                                )
                                when (result) {
                                    is CredentialResult.Success -> {
                                        showReauthenticationDialog = false
                                        viewModel.reauthenticateAndRetry(result.idToken)
                                    }
                                    is CredentialResult.Failure -> {
                                        snackbarHostState.showSnackbar(result.message.asString(context))
                                    }
                                    is CredentialResult.Canceled -> {
                                        // User swiped away - keep dialog active
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text(if (isAnonymous) "Verify Session" else "Re-authenticate with Google")
                }
            }
        )
    }

    if (showLogoutConfirmation) {
        val draftCount by viewModel.unfinishedDraftCount.collectAsStateWithLifecycle()

        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            shape = ModalShape,
            title = { Text(if (draftCount > 0) "Unfinished Drafts" else "Logout?") },
            text = { 
                Column {
                    if (draftCount > 0) {
                        val countText = if (draftCount == 1) "1 unfinished Artifact in Drafts" else "$draftCount unfinished Artifacts in Drafts"
                        Text(
                            "You have $countText.\n\n" +
                            "These Drafts are saved securely on this device and will not be deleted when you log out. " +
                            "They'll remain available when you log back into this account on this device.\n\n" +
                            "Drafts won't automatically appear on another device. " +
                            "If you want an Artifact to be available across devices, you can publish it anytime (publishing is optional).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text("Are you sure you want to log out?") 
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmation = false
                        viewModel.logout()
                    }
                ) {
                    Text("Logout", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showExportConfirmation) {
        AlertDialog(
            onDismissRequest = { showExportConfirmation = false },
            shape = ModalShape,
            title = { Text("Export Data?") },
            text = { Text("Do you really want to export all your artifacts and recordings? This will create a ZIP archive that can be opened on any device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportConfirmation = false
                        exportLauncher.launch("artifact_export_${System.currentTimeMillis()}.zip")
                    }
                ) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showNotificationRationale) {
        NotificationRationaleDialog(
            onConfirm = {
                showNotificationRationale = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onDismiss = {
                showNotificationRationale = false
                pendingActionAfterPermission = null
            }
        )
    }
}
