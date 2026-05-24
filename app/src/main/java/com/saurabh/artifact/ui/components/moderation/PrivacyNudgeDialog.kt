package com.saurabh.artifact.ui.components.moderation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.saurabh.artifact.ui.theme.Spacing

/**
 * A "Reflective Pause" dialog that nudges the user when a potential identity leak is detected.
 * Designed with soft colors and empathetic language.
 */
@Composable
fun PrivacyNudgeDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    leaks: List<String> = emptyList()
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.padding(Spacing.Large)
        ) {
            Column(
                modifier = Modifier
                    .padding(Spacing.ExtraLarge)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.Large)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )

                Text(
                    text = "A Moment of Reflection",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "In this space, your anonymity is your protection. We noticed some details that might connect this artifact to your external identity. Would you like to soften these before sharing?",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (leaks.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                        ) {
                            leaks.forEach { leak ->
                                Text(
                                    text = "• $leak",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Review & Edit")
                    }

                    TextButton(
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Publish Anyway",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}
