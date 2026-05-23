package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.FeedbackType
import com.saurabh.artifact.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackSheet(
    onFeedbackSelected: (FeedbackType) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Large)
                .padding(bottom = Spacing.ExtraLarge)
        ) {
            Text(
                text = "How does this feel?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Text(
                text = "Your feedback is private and helps us keep Artifact a safe space for everyone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.Small)
            )

            Spacer(modifier = Modifier.height(Spacing.Medium))

            FeedbackType.entries.forEach { type ->
                FeedbackOption(
                    type = type,
                    icon = getIconForFeedback(type),
                    onClick = {
                        onFeedbackSelected(type)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun FeedbackOption(
    type: FeedbackType,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (type == FeedbackType.SAFETY_CONCERN) 
                       MaterialTheme.colorScheme.error 
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(Spacing.Large))
            
            Column {
                Text(
                    text = type.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (type == FeedbackType.SAFETY_CONCERN) 
                           MaterialTheme.colorScheme.error 
                           else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = type.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun getIconForFeedback(type: FeedbackType): ImageVector {
    return when (type) {
        FeedbackType.NOT_FOR_ME -> Icons.Rounded.Block
        FeedbackType.TOO_INTENSE -> Icons.Rounded.Waves
        FeedbackType.REPETITIVE -> Icons.Rounded.Repeat
        FeedbackType.SAFETY_CONCERN -> Icons.Rounded.HealthAndSafety
    }
}
