package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.FeedRecommendationReason
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.util.ShareEligibility
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactOptionsSheet(
    isOwner: Boolean,
    onReportClick: () -> Unit,
    onDismiss: () -> Unit,
    onDeleteClick: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onShareClick: () -> Unit = {},
    onWhyThisClick: () -> Unit = {},
    recommendationReason: FeedRecommendationReason? = null,
    isPublic: Boolean = true,
    isDraft: Boolean = false,
    showDelete: Boolean = true,
    showSettings: Boolean = true
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var isDismissing by remember { mutableStateOf(false) }

    fun dismissWithAction(action: () -> Unit) {
        if (isDismissing) return
        isDismissing = true
        coroutineScope.launch {
            try {
                sheetState.hide()
            } finally {
                action()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isDismissing) {
                isDismissing = true
                onDismiss()
            }
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Large)
                .padding(top = 8.dp, bottom = Spacing.ExtraLarge)
        ) {
            Text(
                text = "Options",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(Spacing.Medium))

            // Centralized Share Eligibility
            if (ShareEligibility.canShare(isPublic, isDraft)) {
                OptionItem(
                    label = "Share Artifact",
                    description = "Invite others into this moment",
                    icon = Icons.Rounded.Share,
                    onClick = { dismissWithAction(onShareClick) }
                )
            }

            // Explainability: Why this Artifact?
            if (recommendationReason != null && recommendationReason != FeedRecommendationReason.DISCOVERY) {
                OptionItem(
                    label = "Why this Artifact?",
                    description = "Understand how this voice reached you",
                    icon = Icons.Rounded.Info,
                    onClick = { dismissWithAction(onWhyThisClick) }
                )
            }

            if (isOwner) {
                if (showSettings) {
                    OptionItem(
                        label = "Resonance Settings",
                        description = "Change who can see support counts",
                        icon = Icons.Rounded.Settings,
                        onClick = { dismissWithAction(onSettingsClick) }
                    )
                }

                if (showDelete) {
                    OptionItem(
                        label = "Delete Artifact",
                        description = "Permanently remove this from your journey",
                        icon = Icons.Rounded.DeleteOutline,
                        isError = true,
                        onClick = { dismissWithAction(onDeleteClick) }
                    )
                }
            } else {
                OptionItem(
                    label = "Not for me",
                    description = "See less content like this in your feed",
                    icon = Icons.Rounded.RemoveCircleOutline,
                    onClick = { dismissWithAction(onFeedbackClick) }
                )
            }

            OptionItem(
                label = "Report this artifact",
                description = "Flag content that violates community guidelines",
                icon = Icons.Rounded.Report,
                isError = true,
                onClick = { dismissWithAction(onReportClick) }
            )
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
