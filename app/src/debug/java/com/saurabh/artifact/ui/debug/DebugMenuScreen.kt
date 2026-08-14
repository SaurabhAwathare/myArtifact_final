package com.saurabh.artifact.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.ui.settings.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugMenuScreen(
    onBackClick: () -> Unit,
    onNavigateToModeration: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer Options") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            DebugSection(title = "Technical Info") {
                DebugInfoItem(title = "User ID", value = uiState.userId, icon = Icons.Default.Fingerprint)
                DebugInfoItem(title = "Device ID", value = uiState.deviceId, icon = Icons.Default.Smartphone)
                DebugInfoItem(title = "App Version", value = uiState.appVersion, icon = Icons.Default.Info)
            }

            DebugSection(title = "Feature Toggles") {
                SettingsSwitch(
                    title = "Use Mock Topics",
                    subtitle = "Toggle hardcoded topics for draft creation",
                    checked = uiState.settings.useMockTopics,
                    onCheckedChange = { viewModel.toggleMockTopics(it) },
                    icon = Icons.Default.Science
                )
                SettingsSwitch(
                    title = "Debug Overlays",
                    subtitle = "Show technical overlays (Planned)",
                    checked = uiState.settings.showDebugOverlays,
                    onCheckedChange = { viewModel.toggleDebugOverlays(it) },
                    icon = Icons.Default.Layers
                )
            }

            DebugSection(title = "Admin Tools") {
                SettingsClickable(
                    title = "Moderation Dashboard",
                    subtitle = "Review user reports and safety alerts",
                    icon = Icons.Default.Gavel,
                    onClick = onNavigateToModeration
                )
            }

            DebugSection(title = "App Health") {
                SettingsClickable(
                    title = "Clear Local Cache",
                    subtitle = "Force refresh of all local data stores",
                    icon = Icons.Default.DeleteSweep,
                    onClick = { /* BACKLOG: Implement clear cache logic for Production Debugging (Phase 24+) */ }
                )
            }

            DebugSection(title = "Profile Audit Tools") {
                SettingsClickable(
                    title = "Setup Healthy Profile",
                    subtitle = "Set schemaVersion to latest",
                    icon = Icons.Default.CheckCircle,
                    onClick = { viewModel.prepareHealthyAudit() }
                )
                SettingsClickable(
                    title = "Setup Legacy Profile",
                    subtitle = "Set schemaVersion to 1",
                    icon = Icons.Default.History,
                    onClick = { viewModel.prepareLegacyAudit() }
                )
                SettingsClickable(
                    title = "Setup Corrupted Profile",
                    subtitle = "Inject type mismatch in count field",
                    icon = Icons.Default.BugReport,
                    onClick = { viewModel.prepareCorruptedAudit() }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Artifact Developer Tools - Internal Use Only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DebugSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSection(title = title, content = content)
}

@Composable
private fun DebugInfoItem(
    title: String,
    value: String,
    icon: ImageVector
) {
    SettingsInfoItem(
        title = title,
        subtitle = value,
        icon = icon
    )
}
