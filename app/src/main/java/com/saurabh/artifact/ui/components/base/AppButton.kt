package com.saurabh.artifact.ui.components.base

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.saurabh.artifact.ui.components.motion.PressableScale
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.ui.util.FeedbackUtils

/**
 * Standardized Button for the Artifact app.
 * Enforces a pill shape (CircleShape) and consistent padding.
 * Provides tactile scale feedback on press.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    disabledReason: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.Large, vertical = Spacing.Medium),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    
    val actualEnabled = enabled && !isLoading
    
    Box(modifier = modifier) {
        PressableScale(
            modifier = Modifier.fillMaxWidth(),
            onClick = null, // Handled by Button
            enabled = actualEnabled,
            interactionSource = interactionSource
        ) {
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = actualEnabled,
                shape = CircleShape,
                contentPadding = contentPadding,
                interactionSource = interactionSource
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Spacing.Large),
                        strokeWidth = Spacing.ExtraSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Row(
                        modifier = Modifier.animateContentSize(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(Spacing.Large)
                            )
                            Spacer(modifier = Modifier.width(Spacing.Small))
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        // Intercept clicks when disabled to explain why
        if (!actualEnabled && !isLoading && (disabledReason != null)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        FeedbackUtils.explainDisabledAction(context, haptic, disabledReason)
                    }
            )
        }
    }
}
