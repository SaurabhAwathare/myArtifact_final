package com.saurabh.artifact.ui.comment.components

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.comment.CommentUnlockState
import com.saurabh.artifact.ui.components.MindfulTextField
import com.saurabh.artifact.ui.components.base.AppButton
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

/**
 * A stateless component for composing a new comment.
 *
 * @param text The current text in the composer.
 * @param isSubmitting Whether a submission is currently in progress.
 * @param unlockState The authoritative unlock status of the artifact.
 * @param onTextChanged Callback when the text changes.
 * @param onSubmit Callback when the submit action is triggered.
 */
@Composable
fun CommentComposer(
    text: String,
    isSubmitting: Boolean,
    unlockState: CommentUnlockState,
    onTextChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryUnlock: () -> Unit = {}
) {
    val isUnlocked = unlockState == CommentUnlockState.UNLOCKED
    Log.d("COMMENT_FOCUS", "CommentComposer: unlockState=$unlockState isUnlocked=$isUnlocked")
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Large, vertical = Spacing.Medium)
    ) {
        if (!isUnlocked) {
            UnlockMessage(state = unlockState, onRetry = onRetryUnlock)
            Spacer(modifier = Modifier.height(Spacing.Medium))
        }

        MindfulTextField(
            value = text,
            onValueChange = onTextChanged,
            placeholder = if (isUnlocked) "Add a thoughtful response..." else "Listen before you respond",
            enabled = true, // Forced for testing
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(Spacing.Medium))
        
        AppButton(
            text = "Respond",
            onClick = onSubmit,
            isLoading = isSubmitting,
            enabled = isUnlocked && text.isNotBlank() && !isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun UnlockMessage(state: CommentUnlockState, onRetry: () -> Unit) {
    val message = when (state) {
        CommentUnlockState.LOCKED -> "This conversation requires active listening. Finish the artifact to unlock comments."
        CommentUnlockState.SYNCING -> "Syncing your listening progress..."
        CommentUnlockState.VERIFYING -> "Verifying listening completion with server..."
        CommentUnlockState.ERROR -> "Failed to verify listening. Please check your connection."
        CommentUnlockState.TIMEOUT -> "Verification is taking longer than expected. You can try refreshing the state."
        CommentUnlockState.UNLOCKED -> ""
    }

    if (message.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state == CommentUnlockState.ERROR || state == CommentUnlockState.TIMEOUT) 
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            if (state == CommentUnlockState.TIMEOUT || state == CommentUnlockState.ERROR) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Retry", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun CommentComposerPreview() {
    ArtifactTheme {
        CommentComposer(
            text = "",
            isSubmitting = false,
            unlockState = CommentUnlockState.UNLOCKED,
            onTextChanged = {},
            onSubmit = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun CommentComposerLockedPreview() {
    ArtifactTheme {
        CommentComposer(
            text = "",
            isSubmitting = false,
            unlockState = CommentUnlockState.LOCKED,
            onTextChanged = {},
            onSubmit = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun CommentComposerVerifyingPreview() {
    ArtifactTheme {
        CommentComposer(
            text = "",
            isSubmitting = false,
            unlockState = CommentUnlockState.VERIFYING,
            onTextChanged = {},
            onSubmit = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun CommentComposerTypingPreview() {
    ArtifactTheme {
        CommentComposer(
            text = "This really spoke to me...",
            isSubmitting = false,
            unlockState = CommentUnlockState.UNLOCKED,
            onTextChanged = {},
            onSubmit = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun CommentComposerSubmittingPreview() {
    ArtifactTheme {
        CommentComposer(
            text = "This really spoke to me...",
            isSubmitting = true,
            unlockState = CommentUnlockState.UNLOCKED,
            onTextChanged = {},
            onSubmit = {}
        )
    }
}
