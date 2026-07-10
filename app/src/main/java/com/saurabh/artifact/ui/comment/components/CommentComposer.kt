package com.saurabh.artifact.ui.comment.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.components.MindfulTextField
import com.saurabh.artifact.ui.components.base.AppButton
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

/**
 * A stateless component for composing a new comment.
 *
 * @param text The current text in the composer.
 * @param isSubmitting Whether a submission is currently in progress.
 * @param onTextChanged Callback when the text changes.
 * @param onSubmit Callback when the submit action is triggered.
 */
@Composable
fun CommentComposer(
    text: String,
    isSubmitting: Boolean,
    onTextChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Large, vertical = Spacing.Medium)
    ) {
        MindfulTextField(
            value = text,
            onValueChange = onTextChanged,
            placeholder = "Add a thoughtful response...",
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(Spacing.Medium))
        
        AppButton(
            text = "Respond",
            onClick = onSubmit,
            isLoading = isSubmitting,
            enabled = text.isNotBlank() && !isSubmitting,
            modifier = Modifier.fillMaxWidth()
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
            onTextChanged = {},
            onSubmit = {}
        )
    }
}
