package com.saurabh.artifact.ui.comment.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.ui.components.base.AppEmptyState
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

/**
 * A reusable component to display a list of comments with pagination support.
 *
 * @param comments The list of comments to display.
 * @param isInitialLoading Whether the first page is currently loading.
 * @param isLoadingNextPage Whether a subsequent page is currently loading.
 * @param hasMorePages Whether there are more pages to load.
 * @param currentAnonymousId The persona ID of the currently authenticated user.
 * @param onLoadNextPage Callback to trigger loading of the next page.
 * @param onDeleteComment Callback when a comment is requested to be deleted.
 */
@Composable
fun CommentList(
    comments: List<Comment>,
    isInitialLoading: Boolean,
    isLoadingNextPage: Boolean,
    hasMorePages: Boolean,
    currentAnonymousId: String,
    onLoadNextPage: () -> Unit,
    onDeleteComment: (Comment) -> Unit,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Pagination logic: trigger when near the end of the list
    val shouldLoadMore = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1

            hasMorePages && !isLoadingNextPage && !isInitialLoading && lastVisibleItemIndex > (totalItemsNumber - 3)
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            onLoadNextPage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isInitialLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        } else if (comments.isEmpty()) {
            AppEmptyState(
                title = "No comments yet.",
                description = "Be the first to thoughtfully respond.",
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(comments, key = { it.id }) { comment ->
                    CommentItem(
                        comment = comment,
                        isOwner = comment.author.anonymousId == currentAnonymousId,
                        onDeleteClick = { onDeleteComment(comment) },
                        onProfileClick = onProfileClick
                    )
                }

                if (isLoadingNextPage) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.Medium),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun CommentListPreview() {
    ArtifactTheme {
        CommentList(
            comments = listOf(
                Comment(id = "1", text = "Beautifully said.", author = com.saurabh.artifact.model.AuthorSnapshot(name = "User 1")),
                Comment(id = "2", text = "This reminds me of a poem I once read.", author = com.saurabh.artifact.model.AuthorSnapshot(name = "User 2"))
            ),
            isInitialLoading = false,
            isLoadingNextPage = false,
            hasMorePages = true,
            currentAnonymousId = "me",
            onLoadNextPage = {},
            onDeleteComment = {},
            onProfileClick = {}
        )
    }
}
