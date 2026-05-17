package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactManagementBottomSheet(
    isDraft: Boolean,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onViewCommentsClick: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ArtifactTheme.colors.surfaceHearth,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.1f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.Large)
        ) {
            ManagementActionItem(
                icon = Icons.Rounded.Edit,
                label = if (isDraft) "Rename Draft" else "Rename Artifact",
                onClick = {
                    onRenameClick()
                    onDismiss()
                }
            )

            if (!isDraft) {
                ManagementActionItem(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    label = "View Comments",
                    onClick = {
                        onViewCommentsClick()
                        onDismiss()
                    }
                )
            }

            ManagementActionItem(
                icon = Icons.Rounded.DeleteOutline,
                label = if (isDraft) "Delete Draft" else "Delete Artifact",
                textColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                onClick = {
                    onDeleteClick()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun ManagementActionItem(
    icon: ImageVector,
    label: String,
    textColor: Color = ArtifactTheme.colors.onSurfaceMain,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Large, vertical = Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.Large))
            Text(
                text = label,
                style = ArtifactTheme.typography.bodyLarge,
                color = textColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ArtifactTheme.colors.surfaceHearth,
        title = {
            Text(
                "Rename reflection",
                style = ArtifactTheme.typography.headlineSmall,
                color = ArtifactTheme.colors.onSurfaceMain
            )
        },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("Untitled Moment", color = Color.White.copy(alpha = 0.3f)) },
                textStyle = ArtifactTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ArtifactTheme.colors.waveformActive,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedTextColor = ArtifactTheme.colors.onSurfaceMain,
                    unfocusedTextColor = ArtifactTheme.colors.onSurfaceMain
                ),
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank()
            ) {
                Text("Save", color = ArtifactTheme.colors.waveformActive)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.4f))
            }
        }
    )
}

@Composable
fun DeleteConfirmationDialog(
    isPublished: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ArtifactTheme.colors.surfaceHearth,
        icon = {
            Icon(
                Icons.Rounded.DeleteOutline,
                null,
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )
        },
        title = {
            Text(
                if (isPublished) "Delete this artifact?" else "Delete this draft?",
                style = ArtifactTheme.typography.headlineSmall,
                color = ArtifactTheme.colors.onSurfaceMain
            )
        },
        text = {
            Text(
                if (isPublished) {
                    "This artifact will disappear from the archive for everyone.\n\nThis action cannot be undone."
                } else {
                    "This action cannot be undone."
                },
                style = ArtifactTheme.typography.bodyMedium,
                color = ArtifactTheme.colors.onSurfaceMuted
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.4f))
            }
        }
    )
}
