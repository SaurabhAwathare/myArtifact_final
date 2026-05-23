package com.saurabh.artifact.ui.player.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.saurabh.artifact.ui.theme.EmberGlow

@Composable
fun AdvancedControlsSheet(
    isSilenceSkipEnabled: Boolean,
    onSilenceSkipToggle: (Boolean) -> Unit,
    sleepTimerMillisRemaining: Long?,
    onSleepTimerSelected: (Int) -> Unit,
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onReportClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, excludeFromSystemGesture = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(100f)
        ) {
            // Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(enabled = true, onClick = onDismiss)
            )

            // Sheet Content
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                    )
                    .clickable(enabled = true, onClick = { /* Consumed */ })
                    .padding(horizontal = 24.dp)
                    .padding(top = 12.dp, bottom = 48.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(2.dp))
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 24.dp)
                )

                Text(
                    text = "Listening Enhancements",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 24.dp, top = 16.dp)
                )

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
                            selected = if (mins == 0) sleepTimerMillisRemaining == null else false,
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

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )

                ListItem(
                    headlineContent = { Text("Report Artifact", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Rounded.Report, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { onReportClick() }
                )
            }
        }
    }
}
