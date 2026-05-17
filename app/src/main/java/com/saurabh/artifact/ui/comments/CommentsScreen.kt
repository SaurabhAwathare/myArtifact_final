package com.saurabh.artifact.ui.comments

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.ui.feed.CommentViewModel
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.Obsidian900

@Composable
fun CommentsScreen(
    artifactId: String,
    ownerId: String,
    onBack: () -> Unit,
    viewModel: CommentViewModel = hiltViewModel()
) {
    android.util.Log.d("ReviewDebug", "CommentsScreen entering Composition for artifactId=$artifactId")
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isLocked) {
        android.util.Log.d("ReviewDebug", "Compose observed isLocked=${uiState.isLocked}")
    }

    LaunchedEffect(artifactId, ownerId) {
        android.util.Log.d("ReviewDebug", "CommentsScreen LaunchedEffect triggered for artifactId=$artifactId")
        viewModel.loadComments(artifactId, ownerId)
    }

    Scaffold(
        containerColor = ArtifactTheme.colors.surfaceHearth,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = ArtifactTheme.colors.onSurfaceMain)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    "Echoes", 
                    style = ArtifactTheme.typography.titleMedium, 
                    color = ArtifactTheme.colors.onSurfaceMain,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = uiState.isLocked,
                label = "CommentUnlockTransition",
                transitionSpec = {
                    fadeIn(animationSpec = tween(1000)) togetherWith fadeOut(animationSpec = tween(500))
                }
            ) { isLocked ->
                if (isLocked) {
                    LockedCommentView(progress = uiState.listeningProgress)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(uiState.comments) { comment ->
                            CommentCard(comment = comment)
                        }
                        
                        if (uiState.comments.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No echoes yet. Be the first to respond.",
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
}

@Composable
fun CommentCard(comment: com.saurabh.artifact.model.ArtifactComment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.authorEmoji, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = comment.authorDisplayName ?: "Anonymous Soul",
                    style = ArtifactTheme.typography.labelLarge,
                    color = ArtifactTheme.colors.onSurfaceMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = comment.content,
                style = ArtifactTheme.typography.bodyLarge,
                color = ArtifactTheme.colors.onSurfaceMain.copy(alpha = 0.9f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CommentReactionChip("🫂")
                CommentReactionChip("💫")
                CommentReactionChip("🌊")
            }
        }
    }
}

@Composable
fun CommentReactionChip(emoji: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = emoji, fontSize = 14.sp)
    }
}
