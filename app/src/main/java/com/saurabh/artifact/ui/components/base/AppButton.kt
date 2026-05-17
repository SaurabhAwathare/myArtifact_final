package com.saurabh.artifact.ui.components.base

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.saurabh.artifact.ui.components.motion.PressableScale
import com.saurabh.artifact.ui.theme.Spacing

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
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.Large, vertical = Spacing.Medium)
) {
    PressableScale(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier
    ) {
        Button(
            onClick = { /* Handled by PressableScale */ },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && !isLoading,
            shape = CircleShape,
            contentPadding = contentPadding
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
}


