package com.saurabh.artifact.ui.comment.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.Comment
import com.saurabh.artifact.ui.components.ArtifactSigil
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.util.TimeUtils

/**
 * A reusable component to display a single comment.
 *
 * @param comment The comment to display.
 * @param isOwner Whether the current user is the author of the comment.
 * @param onDeleteClick Callback when the delete action is triggered.
 */
@Composable
fun CommentItem(
    comment: Comment,
    isOwner: Boolean,
    onDeleteClick: () -> Unit,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Small, horizontal = Spacing.Large),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
    ) {
        ArtifactSigil(
            config = comment.author.sigilConfig,
            size = 36.dp,
            isStatic = true,
            modifier = Modifier.clickable { onProfileClick(comment.creatorId) }
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                Text(
                    text = comment.author.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onProfileClick(comment.creatorId) }
                )
                
                if (comment.author.sigil.isNotEmpty()) {
                    Text(
                        text = comment.author.sigil,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = TimeUtils.getRelativeTime(comment.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = comment.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                lineHeight = 20.sp
            )
        }

        if (isOwner) {
            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.Top)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete Comment",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun CommentItemPreview() {
    ArtifactTheme {
        CommentItem(
            comment = Comment(
                text = "This reflection really resonates with me. Thank you for sharing.",
                author = AuthorSnapshot(name = "Luminous Soul", sigil = "A1"),
                createdAt = com.google.firebase.Timestamp.now()
            ),
            isOwner = false,
            onDeleteClick = {},
            onProfileClick = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A)
@Composable
fun CommentItemOwnerPreview() {
    ArtifactTheme {
        CommentItem(
            comment = Comment(
                text = "I'm glad I could capture this moment of clarity.",
                author = AuthorSnapshot(name = "Silent Wanderer", sigil = "W2"),
                createdAt = com.google.firebase.Timestamp.now()
            ),
            isOwner = true,
            onDeleteClick = {},
            onProfileClick = {}
        )
    }
}
