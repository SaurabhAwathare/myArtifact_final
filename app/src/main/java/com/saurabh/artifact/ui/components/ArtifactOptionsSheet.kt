package com.saurabh.artifact.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.ui.theme.ZIndexTokens

@Composable
fun ArtifactOptionsSheet(
    isOwner: Boolean,
    onReportClick: () -> Unit,
    onDismiss: () -> Unit,
    onDeleteClick: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, excludeFromSystemGesture = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(ZIndexTokens.MODAL_OVERLAYS)
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
                    .pointerInput(Unit) {
                        detectTapGestures { /* Consume clicks to prevent dismissing when clicking sheet itself */ }
                    }
                    .padding(horizontal = Spacing.Large)
                    .padding(top = 12.dp, bottom = Spacing.ExtraLarge)
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(2.dp))
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Options",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(Spacing.Medium))

                if (isOwner) {
                    OptionItem(
                        label = "Resonance Settings",
                        description = "Change who can see support counts",
                        icon = Icons.Rounded.Settings,
                        onClick = {
                            onSettingsClick()
                            onDismiss()
                        }
                    )

                    OptionItem(
                        label = "Delete Artifact",
                        description = "Permanently remove this from your journey",
                        icon = Icons.Rounded.DeleteOutline,
                        isError = true,
                        onClick = {
                            onDeleteClick()
                            onDismiss()
                        }
                    )
                } else {
                    OptionItem(
                        label = "Give feedback",
                        description = "Tell us how this content feels",
                        icon = Icons.Rounded.ChatBubbleOutline,
                        onClick = {
                            onFeedbackClick()
                            onDismiss()
                        }
                    )
                }

                OptionItem(
                    label = "Report this artifact",
                    description = "Flag content that violates community guidelines",
                    icon = Icons.Rounded.Report,
                    isError = true,
                    onClick = {
                        onReportClick()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun OptionItem(
    label: String,
    description: String,
    icon: ImageVector,
    isError: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(Spacing.Large))
            
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
