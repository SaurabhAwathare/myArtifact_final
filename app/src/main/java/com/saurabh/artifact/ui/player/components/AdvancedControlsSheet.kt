package com.saurabh.artifact.ui.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.EmberGlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedControlsSheet(
    isSilenceSkipEnabled: Boolean,
    onSilenceSkipToggle: (Boolean) -> Unit,
    sleepTimerMillisRemaining: Long?,
    onSleepTimerSelected: (Int) -> Unit,
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onReportClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var isDismissing by remember { mutableStateOf(false) }

    fun dismissWithAction(action: () -> Unit) {
        if (isDismissing) return
        isDismissing = true
        coroutineScope.launch {
            try {
                sheetState.hide()
            } finally {
                action()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isDismissing) {
                isDismissing = true
                onDismiss()
            }
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 48.dp),
        ) {
            Text(
                text = "Listening Enhancements",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 24.dp, top = 8.dp),
            )

            ListItem(
                headlineContent = { Text("Silence Awareness") },
                supportingContent = { Text("Respectfully bridge long pauses while keeping breaths.") },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = isSilenceSkipEnabled,
                        onCheckedChange = onSilenceSkipToggle,
                        colors = SwitchDefaults.colors(checkedThumbColor = EmberGlow),
                    )
                },
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            )

            Text(
                text = "Playback Speed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            PlaybackSpeedSelector(
                currentSpeed = currentSpeed,
                onSpeedSelected = onSpeedSelected,
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            )

            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0, 15, 30, 45, 60).forEach { mins ->
                    FilterChip(
                        selected = if (mins == 0) sleepTimerMillisRemaining == null else false,
                        onClick = { onSleepTimerSelected(mins) },
                        label = { Text(if (mins == 0) "Off" else "${mins}m") },
                    )
                }
            }

            if (sleepTimerMillisRemaining != null) {
                val mins = sleepTimerMillisRemaining / 60000
                val secs = (sleepTimerMillisRemaining % 60000) / 1000
                Text(
                    text = "Timer active: ${mins}:${secs.toString().padStart(2, '0')} remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberGlow,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            )

            ListItem(
                headlineContent = { Text("Report Artifact", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Rounded.Report, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { dismissWithAction(onReportClick) },
            )
        }
    }
}
