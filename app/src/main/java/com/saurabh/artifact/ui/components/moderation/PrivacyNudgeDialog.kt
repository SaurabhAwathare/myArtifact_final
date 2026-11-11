package com.saurabh.artifact.ui.components.moderation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.CardShape
import com.saurabh.artifact.ui.theme.ModalShape
import com.saurabh.artifact.ui.theme.Obsidian700
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.Spacing

/**
 * A "Reflective Pause" dialog that nudges the user when a potential identity leak is detected.
 * Designed with soft colors, approved modal geometry (28dp), and empathetic language.
 */
@Composable
fun PrivacyNudgeDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    leaks: List<String> = emptyList()
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ModalShape,
            color = ArtifactTheme.colors.surfaceHearth,
            border = BorderStroke(1.dp, Obsidian700),
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
                    tint = ArtifactTheme.colors.goldAura,
                    modifier = Modifier.size(48.dp)
                )

                Text(
                    text = "A Moment of Reflection",
                    style = ArtifactTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = ArtifactTheme.colors.onSurfaceMain
                )

                Text(
                    text = "In this space, your anonymity is your protection. We noticed some details that might connect this artifact to your external identity. Would you like to soften these before sharing?",
                    style = ArtifactTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = ArtifactTheme.colors.onSurfaceMuted
                )

                if (leaks.isNotEmpty()) {
                    Surface(
                        color = ArtifactTheme.colors.surfaceLoom,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Obsidian700)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
                        ) {
                            leaks.forEach { leak ->
                                Text(
                                    text = "• $leak",
                                    style = ArtifactTheme.typography.labelMedium,
                                    color = ArtifactTheme.colors.statusWarning
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = CardShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ArtifactTheme.colors.goldAura,
                            contentColor = Obsidian950
                        )
                    ) {
                        Text("Review & Edit", fontWeight = FontWeight.Bold)
                    }

                    TextButton(
                        onClick = onConfirm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(
                            text = "Publish Anyway",
                            style = ArtifactTheme.typography.labelLarge,
                            color = ArtifactTheme.colors.onSurfaceMuted
                        )
                    }
                }
            }
        }
    }
}
