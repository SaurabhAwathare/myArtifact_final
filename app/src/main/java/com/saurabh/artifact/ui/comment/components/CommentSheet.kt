package com.saurabh.artifact.ui.comment.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.ui.comment.CommentUiEvent
import com.saurabh.artifact.ui.comment.CommentUiState
import com.saurabh.artifact.ui.components.base.AppButton
import com.saurabh.artifact.ui.components.base.AppEmptyState
import com.saurabh.artifact.ui.theme.LocalUserProfile
import com.saurabh.artifact.ui.theme.Spacing
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * A stateless UI component for the comment system, displayed as a Bottom Sheet.
 *
 * @param uiState The current state of the comment UI.
 * @param events A stream of one-time events from the ViewModel.
 * @param onDismiss Callback to dismiss the sheet.
 * @param onSubmit Callback to submit a new comment.
 * @param onDelete Callback to delete an existing comment.
 * @param onLoadNextPage Callback to load more comments.
 * @param onRetry Callback to retry the initial load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheet(
    uiState: CommentUiState,
    events: SharedFlow<CommentUiEvent>,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onDelete: (Comment) -> Unit,
    onLoadNextPage: () -> Unit,
    onRetry: () -> Unit,
    onRetryUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentUserProfile = LocalUserProfile.current
    val currentUserId = currentUserProfile?.id ?: ""
    
    var inputText by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle One-Time Events (e.g., successful submission)
    LaunchedEffect(events) {
        events.collectLatest { event ->
            when (event) {
                is CommentUiEvent.CommentSubmitted -> {
                    inputText = ""
                }
                is CommentUiEvent.SubmissionFailed -> {
                    snackbarHostState.showSnackbar(
                        message = event.error,
                        duration = SnackbarDuration.Short
                    )
                }
                else -> {}
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.85f)
                    .fillMaxWidth()
            ) {
                // Header
                Text(
                    text = "Comments",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(Spacing.Large)
                )

                // Content Area (List / Loading / Error)
                Box(modifier = Modifier.weight(1f)) {
                    if (uiState.error != null && uiState.comments.isEmpty()) {
                        AppEmptyState(
                            title = "Couldn't load comments",
                            description = "A quiet moment of connection was interrupted.",
                            emoji = "🌑",
                            action = {
                                AppButton(
                                    text = "Retry",
                                    onClick = onRetry
                                )
                            },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        CommentList(
                            comments = uiState.comments,
                            isInitialLoading = uiState.isInitialLoading,
                            isLoadingNextPage = uiState.isLoadingNextPage,
                            hasMorePages = uiState.hasMorePages,
                            currentUserId = currentUserId,
                            onLoadNextPage = onLoadNextPage,
                            onDeleteComment = onDelete,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Composer Area
                CommentComposer(
                    text = inputText,
                    isSubmitting = uiState.isSubmitting,
                    unlockState = uiState.unlockState,
                    onTextChanged = { inputText = it },
                    onSubmit = { onSubmit(inputText) },
                    onRetryUnlock = onRetryUnlock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                )
            }

            // Error Feedback
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp) // Offset above composer
            )
        }
    }
}
