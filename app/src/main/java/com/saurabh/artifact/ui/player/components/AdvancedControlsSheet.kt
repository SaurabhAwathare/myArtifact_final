package com.saurabh.artifact.ui.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.EmberGlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedControlsSheet(
    isSilenceSkipEnabled: Boolean,
    onSilenceSkipToggle: (Boolean) -> Unit,
    sleepTimerMillisRemaining: Long?,
    onSleepTimerSelected: (Int) -> Unit,
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp)
        ) {
            Text(
                text = "Listening Enhancements",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Silence Skipping
            ListItem(
                headlineContent = { Text("Silence Awareness") },
                supportingContent = { Text("Respectfully bridge long pauses while keeping breaths.") },
                leadingContent = { Icon(Icons.Rounded.VolumeOff, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = isSilenceSkipEnabled,
                        onCheckedChange = onSilenceSkipToggle,
                        colors = SwitchDefaults.colors(checkedThumbColor = EmberGlow)
                    )
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp), 
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            // Playback Speed
            Text(
                text = "Playback Speed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            
            PlaybackSpeedSelector(
                currentSpeed = currentSpeed,
                onSpeedSelected = onSpeedSelected
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            // Sleep Timer
            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 15, 30, 45, 60).forEach { mins ->
                    FilterChip(
                        selected = if (mins == 0) sleepTimerMillisRemaining == null else false, // Simplified
                        onClick = { onSleepTimerSelected(mins) },
                        label = { Text(if (mins == 0) "Off" else "${mins}m") }
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
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
