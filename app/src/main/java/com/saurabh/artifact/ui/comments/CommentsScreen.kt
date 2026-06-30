package com.saurabh.artifact.ui.comments

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.isCommentAvailable
import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.ui.components.EmptyHearthState
import com.saurabh.artifact.ui.components.TextCommentItem
import com.saurabh.artifact.ui.components.moderation.ReportSheet
import com.saurabh.artifact.ui.feed.CommentComposer
import com.saurabh.artifact.ui.feed.CommentViewModel
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.ZIndexTokens

@Composable
fun CommentsScreen(
    artifactId: String,
    ownerId: String,
    onBack: () -> Unit,
    viewModel: CommentViewModel = hiltViewModel()
) {
    android.util.Log.d("ReviewDebug", "CommentsScreen entering Composition for artifactId=$artifactId")
    val uiState by viewModel.uiState.collectAsState()
    var reportingCommentId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.engagementStatus) {
        android.util.Log.d("ReviewDebug", "UI Compose observed engagementStatus change: ${uiState.engagementStatus}")
    }

    LaunchedEffect(artifactId, ownerId) {
        android.util.Log.d("ReviewDebug", "CommentsScreen LaunchedEffect triggered for artifactId=$artifactId")
        viewModel.loadComments(artifactId, ownerId)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ArtifactTheme.colors.surfaceHearth)
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.currentUserId == ownerId) "The Hearth" else "Comments",
                    style = ArtifactTheme.typography.titleMedium, 
                    color = ArtifactTheme.colors.onSurfaceMain,
                    fontWeight = FontWeight.Bold
                )
            }

            // Content
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = uiState.engagementStatus,
                    label = "CommentUnlockTransition",
                    transitionSpec = {
                        fadeIn(animationSpec = tween(1000)) togetherWith fadeOut(animationSpec = tween(500))
                    }
                ) { status ->
                    if (!status.isCommentAvailable()) {
                        LockedCommentView(
                            progress = uiState.listeningProgress,
                            requiredCoverage = uiState.requiredCoverage,
                            status = status
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(
                                count = uiState.comments.size,
                                key = { index -> uiState.comments[index].id }
                            ) { index ->
                                val comment = uiState.comments[index]
                                if (comment.visibilityLayer == com.saurabh.artifact.model.VisibilityLayer.SANCTUARY) {
                                    TextCommentItem(
                                        comment = comment,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                } else {
                                    CommentCard(
                                        comment = comment,
                                        currentUserId = uiState.currentUserId,
                                        ownerId = ownerId,
                                        onReport = { reportingCommentId = comment.id },
                                        onReact = { type -> viewModel.reactToComment(comment.id, type) }
                                    )
                                }
                            }
                            
                            if (uiState.comments.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier.fillParentMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (uiState.currentUserId == ownerId) {
                                            EmptyHearthState()
                                        } else {
                                            Text(
                                                "No comments yet. Be the first to share your thoughts.",
                                                style = ArtifactTheme.typography.bodyMedium,
                                                color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Inline Composer
            if (uiState.engagementStatus.isCommentAvailable()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                CommentComposer(
                    artifactId = artifactId,
                    viewModel = viewModel,
                    onClose = {} // Not used in inline mode
                )
            }
        }

        if (reportingCommentId != null) {
            ReportSheet(
                onReportSubmitted = { reason, details ->
                    viewModel.submitReport(artifactId, reportingCommentId, reason, details)
                    reportingCommentId = null
                },
                onDismiss = { reportingCommentId = null }
            )
        }
    }
}

@Composable
fun CommentCard(
    comment: ArtifactComment,
    currentUserId: String,
    ownerId: String,
    onReport: () -> Unit,
    onReact: (ReactionType) -> Unit
) {
    val isAnonymous = comment.authorType == com.saurabh.artifact.model.AuthorType.QUIET_PRESENCE
    val isOwner = currentUserId == ownerId
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAnonymous) {
                ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.3f)
            } else {
                ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.5f)
            }
        ),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, 
            if (isAnonymous) Color.White.copy(alpha = 0.02f) else Color.White.copy(alpha = 0.05f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtifactAvatar(
                        config = AvatarConfig(seed = comment.authorAvatarSeed),
                        size = 32.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = comment.authorAnonymousName ?: "Quiet Presence",
                            style = ArtifactTheme.typography.labelLarge,
                            color = if (isAnonymous) ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.7f) else ArtifactTheme.colors.onSurfaceMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isAnonymous) {
                            Text(
                                text = "Anonymous Presence",
                                style = MaterialTheme.typography.labelSmall,
                                color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                IconButton(onClick = onReport) {
                    Icon(
                        Icons.Outlined.Report,
                        contentDescription = "Report",
                        tint = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = comment.content,
                style = ArtifactTheme.typography.bodyLarge,
                color = if (isAnonymous) {
                    ArtifactTheme.colors.onSurfaceMain.copy(alpha = 0.7f)
                } else {
                    ArtifactTheme.colors.onSurfaceMain.copy(alpha = 0.9f)
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            if (isOwner) {
                CreatorReactionPicker(
                    selectedReaction = comment.creatorReaction,
                    onReactionSelect = onReact
                )
            } else if (comment.creatorReaction != null) {
                CommentReactionChip(
                    emoji = comment.creatorReaction?.emoji ?: "",
                    label = "Acknowledge by creator"
                )
            }
        }
    }
}

@Composable
fun CreatorReactionPicker(
    selectedReaction: ReactionType?,
    onReactionSelect: (ReactionType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReactionType.entries.forEach { type ->
            val isSelected = type == selectedReaction
            Surface(
                onClick = { onReactionSelect(type) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) GoldAura500.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.2.dp, GoldAura500.copy(alpha = 0.5f)) else null,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = type.emoji,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun CommentReactionChip(emoji: String, label: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = emoji, fontSize = 14.sp)
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.6f)
            )
        }
    }
}
