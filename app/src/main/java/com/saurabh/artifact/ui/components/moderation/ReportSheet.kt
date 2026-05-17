package com.saurabh.artifact.ui.components.moderation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.ReportReason
import com.saurabh.artifact.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    onReportSubmitted: (ReportReason, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedReason by remember { mutableStateOf<ReportReason?>(null) }
    var details by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Large)
                .padding(bottom = Spacing.ExtraLarge)
        ) {
            Text(
                text = "Report content",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Help us understand what's wrong. Your report is anonymous.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.Small)
            )

            if (selectedReason == null) {
                Spacer(modifier = Modifier.height(Spacing.Medium))
                ReportReason.entries.forEach { reason ->
                    ReportOption(
                        reason = reason,
                        onClick = { selectedReason = reason }
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(Spacing.Large))
                Text(
                    text = "Reason: ${selectedReason?.name?.replace("_", " ")?.lowercase()?.capitalize()}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Add details (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.Large),
                    minLines = 3,
                    shape = MaterialTheme.shapes.medium
                )

                Button(
                    onClick = {
                        selectedReason?.let { onReportSubmitted(it, details) }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Submit Report")
                }
                
                TextButton(
                    onClick = { selectedReason = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ReportOption(
    reason: ReportReason,
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
            val icon = getIconForReason(reason)
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.Large))
            Text(
                text = reason.name.replace("_", " ").lowercase().capitalize(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun getIconForReason(reason: ReportReason): ImageVector {
    return when (reason) {
        ReportReason.HARASSMENT -> Icons.Rounded.PersonOff
        ReportReason.SELF_HARM -> Icons.Rounded.HealthAndSafety
        ReportReason.HATE_SPEECH -> Icons.Rounded.Gavel
        ReportReason.SEXUAL_CONTENT -> Icons.Rounded.Warning
        ReportReason.PII_EXPOSURE -> Icons.Rounded.VpnKey
        ReportReason.SPAM -> Icons.Rounded.Report
        ReportReason.OTHER -> Icons.Rounded.MoreHoriz
    }
}

// Utility extension for capitalization if not available
private fun String.capitalize() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
