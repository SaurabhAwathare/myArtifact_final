package com.saurabh.artifact.ui.comment.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    modifier: Modifier = Modifier
) {
    val isUnlocked = unlockState == CommentUnlockState.UNLOCKED
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Large, vertical = Spacing.Medium)
    ) {
        if (!isUnlocked) {
            UnlockMessage(state = unlockState)
            Spacer(modifier = Modifier.height(Spacing.Medium))
        }

        MindfulTextField(
            value = text,
            onValueChange = onTextChanged,
            placeholder = if (isUnlocked) "Add a thoughtful response..." else "Listen before you respond",
            enabled = isUnlocked,
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
private fun UnlockMessage(state: CommentUnlockState) {
    val message = when (state) {
        CommentUnlockState.LOCKED -> "This conversation requires active listening. Finish the artifact to unlock comments."
        CommentUnlockState.SYNCING -> "Syncing your listening progress..."
        CommentUnlockState.VERIFYING -> "Verifying listening completion with server..."
        CommentUnlockState.ERROR -> "Failed to verify listening. Please check your connection."
        CommentUnlockState.UNLOCKED -> ""
    }

    if (message.isNotEmpty()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (state == CommentUnlockState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
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
