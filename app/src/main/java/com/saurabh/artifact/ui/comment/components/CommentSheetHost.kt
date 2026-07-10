package com.saurabh.artifact.ui.comment.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.ui.comment.CommentViewModel
import androidx.compose.ui.graphics.Color

/**
 * The integration bridge between the Artifact Player and the Comment logic.
 *
 * Responsibilities:
 * - Obtain and initialize [CommentViewModel].
 * - Orchestrate UI-only logic like confirmation dialogs.
 * - Map ViewModel state/actions to the stateless [CommentSheet].
 */
@Composable
fun CommentSheetHost(
    artifactId: String,
    onDismiss: () -> Unit,
    viewModel: CommentViewModel = hiltViewModel()
) {
    // Initialize the ViewModel with the current artifact ID
    LaunchedEffect(artifactId) {
        viewModel.initialize(artifactId)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var commentToDelete by remember { mutableStateOf<Comment?>(null) }

    // 1. Delete Confirmation Dialog
    if (commentToDelete != null) {
        AlertDialog(
            onDismissRequest = { commentToDelete = null },
            title = { Text("Delete Comment?") },
            text = { Text("This will permanently remove your response from this conversation.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        commentToDelete?.let { viewModel.deleteComment(it) }
                        commentToDelete = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFE91E63))
                }
            },
            dismissButton = {
                TextButton(onClick = { commentToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 2. The Stateless UI
    CommentSheet(
        uiState = uiState,
        events = viewModel.events,
        onDismiss = onDismiss,
        onSubmit = { viewModel.submitComment(it) },
        onDelete = { commentToDelete = it },
        onLoadNextPage = { viewModel.loadNextPage() },
        onRetry = { viewModel.loadInitialComments() }
    )
}
