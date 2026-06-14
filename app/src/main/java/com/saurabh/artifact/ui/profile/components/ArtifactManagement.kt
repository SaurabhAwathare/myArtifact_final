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
    isOwner: Boolean,
    isDraft: Boolean,
    onRenameClick: () -> Unit,
    onPublishClick: () -> Unit = {},
    onReviewClick: () -> Unit = {},
    isListened: Boolean = false,
    reviewProgress: Float = 0f,
    onDeleteClick: () -> Unit,
    onViewCommentsClick: (() -> Unit)? = null,
    isSaved: Boolean = false,
    onUnsaveClick: () -> Unit = {},
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ArtifactTheme.colors.surfaceHearth,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.Large)
        ) {
            if (isOwner) {
                ManagementActionItem(
                    icon = Icons.Rounded.Edit,
                    label = if (isDraft) "Rename Draft" else "Rename Artifact",
                    onClick = {
                        onRenameClick()
                        onDismiss()
                    }
                )

                if (isDraft) {
                    val isReady = isListened
                    val label = if (isReady) "Publish Artifact" else "Review to Publish (${(reviewProgress * 100).toInt()}%)"
                    val icon = if (isReady) Icons.Rounded.Publish else Icons.Rounded.Headset
                    
                    ManagementActionItem(
                        icon = icon,
                        label = label,
                        textColor = if (isReady) ArtifactTheme.colors.waveformActive else ArtifactTheme.colors.onSurfaceMuted,
                        onClick = {
                            if (isReady) onPublishClick() else onReviewClick()
                            onDismiss()
                        }
                    )
                }
            }

            if (isSaved) {
                ManagementActionItem(
                    icon = Icons.Rounded.Bookmark,
                    label = "Release from Archive",
                    onClick = {
                        onUnsaveClick()
                        onDismiss()
                    }
                )
            }

            if (onViewCommentsClick != null && !isDraft) {
                ManagementActionItem(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    label = "View Comments",
                    onClick = {
                        onViewCommentsClick()
                        onDismiss()
                    }
                )
            }

            if (isOwner) {
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
    val maxChars = 70
    val isValid = title.isNotBlank() && title.length <= maxChars

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
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= maxChars + 10) title = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text("Untitled Moment", color = Color.White.copy(alpha = 0.3f)) },
                    textStyle = ArtifactTheme.typography.bodyLarge,
                    isError = title.length > maxChars,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ArtifactTheme.colors.waveformActive,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        focusedTextColor = ArtifactTheme.colors.onSurfaceMain,
                        unfocusedTextColor = ArtifactTheme.colors.onSurfaceMain,
                        errorBorderColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "${title.length}/$maxChars",
                        style = ArtifactTheme.typography.labelSmall,
                        color = if (title.length > maxChars) MaterialTheme.colorScheme.error 
                                else ArtifactTheme.colors.onSurfaceMuted
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = isValid
            ) {
                Text("Update", color = if (isValid) ArtifactTheme.colors.waveformActive 
                                       else ArtifactTheme.colors.waveformActive.copy(alpha = 0.3f))
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
