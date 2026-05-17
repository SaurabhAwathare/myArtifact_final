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
import com.saurabh.artifact.model.ReactionVisibilityMode
import com.saurabh.artifact.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResonanceSettingsSheet(
    currentMode: ReactionVisibilityMode,
    onModeSelected: (ReactionVisibilityMode) -> Unit,
    onDismiss: () -> Unit
) {
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
                text = "Show support from listeners?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Text(
                text = "Choose how visible you want the appreciation to be.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.Small)
            )

            Spacer(modifier = Modifier.height(Spacing.Medium))

            ReactionVisibilityMode.entries.forEach { mode ->
                VisibilityOption(
                    mode = mode,
                    isSelected = mode == currentMode,
                    onClick = {
                        onModeSelected(mode)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun VisibilityOption(
    mode: ReactionVisibilityMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = Spacing.Medium, vertical = Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getIconForVisibility(mode),
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary 
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(Spacing.Large))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = getLabelForVisibility(mode),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = getDescriptionForVisibility(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun getIconForVisibility(mode: ReactionVisibilityMode): ImageVector {
    return when (mode) {
        ReactionVisibilityMode.VISIBLE -> Icons.Rounded.Visibility
        ReactionVisibilityMode.APPROXIMATE -> Icons.Rounded.Waves
        ReactionVisibilityMode.CREATOR_ONLY -> Icons.Rounded.Person
        ReactionVisibilityMode.HIDDEN -> Icons.Rounded.VisibilityOff
    }
}

private fun getLabelForVisibility(mode: ReactionVisibilityMode): String {
    return when (mode) {
        ReactionVisibilityMode.VISIBLE -> "Show support count"
        ReactionVisibilityMode.APPROXIMATE -> "Show support count"
        ReactionVisibilityMode.CREATOR_ONLY -> "Keep support private"
        ReactionVisibilityMode.HIDDEN -> "Keep support private"
    }
}

private fun getDescriptionForVisibility(mode: ReactionVisibilityMode): String {
    return when (mode) {
        ReactionVisibilityMode.VISIBLE -> "Others can see how many people resonated with your words."
        ReactionVisibilityMode.APPROXIMATE -> "Others can see how many people resonated with your words."
        ReactionVisibilityMode.CREATOR_ONLY -> "Only you can see the support you receive."
        ReactionVisibilityMode.HIDDEN -> "Only you can see the support you receive."
    }
}
