package com.saurabh.artifact.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.time.Duration.Companion.seconds
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.R
import com.saurabh.artifact.auth.CredentialResult
import com.saurabh.artifact.ui.components.NotificationRationaleDialog
import com.saurabh.artifact.ui.util.UiText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onLogoutSuccess: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val accountInfo by viewModel.accountInfo.collectAsStateWithLifecycle()
    val isAnonymous by viewModel.isAnonymous.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showReauthenticationDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    var showExportConfirmation by remember { mutableStateOf(false) }
    var showExportSuccess by remember { mutableStateOf(false) }
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
            uri?.let { viewModel.exportData(it) }
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
                is SettingsUiEvent.ExportInitiated -> {
                    showExportSuccess = true
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!isAnonymous && accountInfo != null) {
                    SettingsSection(title = "Account") {
                        SettingsInfoItem(
                            title = accountInfo?.realName?.toUnsecureString() ?: "Anonymous User",
                            subtitle = accountInfo?.email?.toUnsecureString(),
                            icon = Icons.Default.Person,
                            modifier = Modifier.clickable { 
                                if (!isAnonymous && accountInfo != null) {
                                    viewModel.copyEmailToClipboard(context)
                                }
                            },
                            trailingContent = {
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
                            }
                        )
                    }
                }

                SettingsSection(title = "Privacy") {
                    SettingsSwitch(
                        title = "Notifications",
                        subtitle = "Stay connected with reflections",
                        icon = Icons.Default.Notifications,
                        checked = uiState.notificationsEnabled,
                        onCheckedChange = { enabled ->
                            handleNotificationToggle(enabled) { viewModel.updateNotifications(it) }
                        }
                    )
                    SettingsSwitch(
                        title = "Smart Reminders",
                        subtitle = "Gentle nudges for your ritual",
                        icon = Icons.Default.Schedule,
                        checked = uiState.smartRemindersEnabled,
                        onCheckedChange = { enabled ->
                            handleNotificationToggle(enabled) { viewModel.updateSmartReminders(it) }
                        }
                    )
                    SettingsSwitch(
                        title = "Biometric Lock",
                        subtitle = "Secure your private space",
                        icon = Icons.Default.Security,
                        checked = uiState.biometricLockEnabled,
                        onCheckedChange = { viewModel.updateBiometricLock(it) }
                    )
                    SettingsSwitch(
                        title = "Auto-lock",
                        subtitle = "Lock app when inactive",
                        icon = Icons.Default.Shield,
                        checked = uiState.autoLockEnabled,
                        onCheckedChange = { viewModel.updateAutoLock(it) }
                    )
                    SettingsSwitch(
                        title = "Stealth Mode",
                        subtitle = "Hide app from recent tasks",
                        icon = Icons.Default.Security,
                        checked = uiState.stealthModeEnabled,
                        onCheckedChange = { viewModel.updateStealthMode(it) }
                    )
                }

                SettingsSection(title = "Data") {
                    SettingsClickable(
                        title = "Export Data",
                        subtitle = "Download your emotional archive",
                        icon = Icons.Default.Download,
                        onClick = { showExportConfirmation = true }
                    )
                    SettingsClickable(
                        title = "Logout",
                        subtitle = "Sign out of your account",
                        icon = Icons.AutoMirrored.Filled.Logout,
                        onClick = { showLogoutConfirmation = true }
                    )
                    SettingsClickable(
                        title = "Delete Account",
                        subtitle = "Permanently remove your traces",
                        icon = Icons.Default.DeleteForever,
                        textColor = MaterialTheme.colorScheme.error,
                        onClick = { showDeleteConfirmation = true }
                    )
                }
            }
            
            if (isDeleting) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Account?") },
            text = { Text("This will permanently delete your anonymous profile and all your journal entries. This action is irreversible.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.initiateDelete()
                    }
                ) {
                    Text("Delete Permanently", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showReauthenticationDialog) {
        AlertDialog(
            onDismissRequest = { showReauthenticationDialog = false },
            title = { Text("Authentication Required") },
            text = { 
                Text(
                    if (isAnonymous) "To delete your anonymous account, we need to verify your current session."
                    else "For your security, please sign in again before deleting your account."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReauthenticationDialog = false
                        if (isAnonymous) {
                            viewModel.reauthenticateAndRetry()
                        } else {
                            coroutineScope.launch {
                                val result = viewModel.credentialHelper.getGoogleCredential(
                                    context = context,
                                    serverClientId = serverClientId,
                                    filterByAuthorizedAccounts = false // For re-auth, we might want to allow choosing again
                                )
                                when (result) {
                                    is CredentialResult.Success -> {
                                        viewModel.reauthenticateAndRetry(result.idToken)
                                    }
                                    is CredentialResult.Failure -> {
                                        snackbarHostState.showSnackbar(result.message.asString(context))
                                    }
                                    is CredentialResult.Canceled -> {
                                        // User swiped away
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text(if (isAnonymous) "Verify & Delete" else "Sign in to Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReauthenticationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text("Logout?") },
            text = { 
                Text(
                    if (uiState.isAnonymousMode) 
                        "You are currently using an anonymous account. Logging out may cause you to lose access to your data permanently unless you have backed it up."
                    else 
                        "Are you sure you want to log out?"
                ) 
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
            title = { Text("Export Data?") },
            text = { Text("Do you really want to export all your local drafts? This will create a ZIP archive of your audio and metadata.") },
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

    if (showExportSuccess) {
        AlertDialog(
            onDismissRequest = { showExportSuccess = false },
            title = { Text("Export Initiated") },
            text = { Text("We have started collecting all your data and artifacts. This process can take up to 72 hours. A download link will be sent to your registered email address once it's ready.") },
            confirmButton = {
                TextButton(onClick = { showExportSuccess = false }) {
                    Text("OK")
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
