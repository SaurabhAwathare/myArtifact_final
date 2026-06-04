package com.saurabh.artifact.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.CommentModerationState
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.VisibilityLayer
import com.saurabh.artifact.ui.components.TextCommentItem
import com.saurabh.artifact.ui.components.EmotionalInsightCard
import com.saurabh.artifact.ui.components.EmptyHearthState
import com.saurabh.artifact.ui.components.ReactionBar
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.Obsidian800
import com.saurabh.artifact.ui.theme.ReflectionWhite
import com.saurabh.artifact.ui.theme.MistGray
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorInboxScreen(
    artifactId: String,
    userId: String,
    onBack: () -> Unit,
    viewModel: CreatorInboxViewModel = hiltViewModel(),
    reactionViewModel: ReactionViewModel = hiltViewModel()
) {
    val reactionState by reactionViewModel.uiState.collectAsState()
    val reflections = viewModel.reflectionsPager.collectAsLazyPagingItems()

    LaunchedEffect(artifactId) {
        viewModel.loadInbox(artifactId, userId)
        reactionViewModel.loadReactions(artifactId)
    }

    Scaffold(
        containerColor = Obsidian950,
        topBar = {
            TopAppBar(
                title = { Text("The Hearth", color = GoldAura400) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GoldAura400)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Obsidian950)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Production Emotional Summary
            if (reactionState.isLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Obsidian800)
                ) {
                    com.saurabh.artifact.ui.components.state.LoadingPlaceholder(
                        modifier = Modifier.fillMaxWidth().height(120.dp).padding(16.dp),
                        pulseColor = GoldAura400.copy(alpha = 0.1f)
                    )
                }
            } else {
                reactionState.counts?.let { counts ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Obsidian800)
                    ) {
                        EmotionalInsightCard(counts = counts)
                    }
                }
            }

            if (reflections.itemCount == 0 && reflections.loadState.refresh is LoadState.NotLoading) {
                EmptyHearthState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(
                        count = reflections.itemCount,
                        key = reflections.itemKey { it.id }
                    ) { index ->
                        val reflection = reflections[index]
                        if (reflection != null) {
                            Column {
                                TextCommentItem(
                                    comment = reflection
                                )
                                
                                Spacer(Modifier.height(8.dp))

                                ModerationControlRow(
                                    comment = reflection,
                                    onApprove = { viewModel.approveComment(reflection.id) },
                                    onFlag = { viewModel.flagComment(reflection.id) }
                                )

                                Spacer(Modifier.height(4.dp))
                                
                                // Private Reaction Controls (Creator Only - Legacy update to new taxonomy)
                                ReactionAcknowledgeRow(
                                    selectedReaction = reflection.creatorReaction,
                                    onReactionSelect = { type ->
                                        viewModel.reactToComment(reflection.id, type)
                                    }
                                )
                            }
                        }
                    }

                    when {
                        reflections.loadState.refresh is LoadState.Loading -> {
                            item {
                                Box(
                                    modifier = Modifier.fillParentMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = GoldAura400)
                                }
                            }
                        }
                        reflections.loadState.append is LoadState.Loading -> {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = GoldAura400)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModerationControlRow(
    comment: ArtifactComment,
    onApprove: () -> Unit,
    onFlag: () -> Unit
) {
    if (comment.visibilityLayer == VisibilityLayer.RESONANCE && comment.moderationState != CommentModerationState.APPROVED) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pending Public View",
                style = MaterialTheme.typography.labelSmall,
                color = GoldAura400.copy(alpha = 0.6f)
            )
            
            Row {
                TextButton(onClick = onFlag) {
                    Text("Flag", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = GoldAura400.copy(alpha = 0.2f), contentColor = GoldAura400),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Approve", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun ReactionAcknowledgeRow(
    selectedReaction: ReactionType?,
    onReactionSelect: (ReactionType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Acknowledge:",
            style = MaterialTheme.typography.labelSmall,
            color = MistGray.copy(alpha = 0.6f)
        )
        
        ReactionType.entries.forEach { type ->
            val isSelected = type == selectedReaction
            Surface(
                onClick = { onReactionSelect(type) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) GoldAura400.copy(alpha = 0.15f) else Color.Transparent,
                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, GoldAura400.copy(alpha = 0.3f)) else null,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = type.emoji,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) GoldAura400 else MistGray
                )
            }
        }
    }
}

