package com.saurabh.artifact.ui.feed

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.model.*
import com.saurabh.artifact.ui.components.ArtifactFeedCard
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

@Composable
fun ForYouFeedScreen(
    viewModel: ForYouFeedViewModel = hiltViewModel(),
    onNavigateToRecord: () -> Unit = {}
) {
    val feedState by viewModel.feedState.collectAsStateWithLifecycle()
    val currentlyPlayingArtifact by viewModel.currentlyPlayingArtifact.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val state = feedState) {
            is FeedCompositionState.Loading -> {
                FeedLoadingState()
            }
            is FeedCompositionState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.message, color = ArtifactTheme.colors.onSurfaceMuted)
                }
            }
            is FeedCompositionState.Success -> {
                val unfinished = state.items.filter { it.isUnfinished }
                val mainFeed = state.items.filter { !it.isUnfinished }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (unfinished.isNotEmpty()) {
                        item {
                            ContinueListeningSection(
                                sessions = unfinished,
                                onSessionClick = { viewModel.playArtifact(it) }
                            )
                        }
                    }

                    items(mainFeed, key = { it.artifact.id }) { feedArtifact ->
                        ArtifactFeedCard(
                            feedArtifact = feedArtifact,
                            isPlaying = isPlaying && currentlyPlayingArtifact?.id == feedArtifact.artifact.id,
                            onPlayClick = { viewModel.playArtifact(feedArtifact) },
                            currentPosition = if (currentlyPlayingArtifact?.id == feedArtifact.artifact.id) currentPosition else 0L,
                            onDeleteClick = { viewModel.deleteArtifact(feedArtifact.artifact.id) },
                            modifier = Modifier.padding(horizontal = Spacing.Large)
                        )
                        
                        // Breath Break Logic
                        val index = mainFeed.indexOf(feedArtifact)
                        if (index > 0 && (index + 1) % 4 == 0) {
                            BreathBreak()
                        }
                    }

                    item {
                        EndStateMessage()
                    }
                }
            }
        }
    }
}

@Composable
fun ContinueListeningSection(
    sessions: List<FeedArtifact>,
    onSessionClick: (FeedArtifact) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = Spacing.Large)) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                tint = ArtifactTheme.colors.waveformActive,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.Small))
            Text(
                "Pick up where you left off",
                style = MaterialTheme.typography.titleSmall,
                color = ArtifactTheme.colors.onSurfaceMain
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.Large),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
        ) {
            items(sessions) { session ->
                Card(
                    modifier = Modifier
                        .width(200.dp)
                        .clickable { onSessionClick(session) },
                    colors = CardDefaults.cardColors(
                        containerColor = ArtifactTheme.colors.surfaceHearth
                    )
                ) {
                    Column(modifier = Modifier.padding(Spacing.Medium)) {
                        Text(
                            session.artifact.title.ifEmpty { "Untitled Reflection" },
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${session.lastPositionMs / 1000}s / ${session.artifact.duration}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = ArtifactTheme.colors.onSurfaceMuted
                        )
                        
                        Spacer(Modifier.height(Spacing.Small))
                        
                        // Small Progress Bar
                        LinearProgressIndicator(
                            progress = { session.lastPositionMs.toFloat() / (session.artifact.duration * 1000L) },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = ArtifactTheme.colors.waveformActive,
                            trackColor = ArtifactTheme.colors.waveformInactive.copy(alpha = 0.1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BreathBreak() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ArtifactTheme.colors.waveformActive.copy(alpha = 0.4f))
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "take a breath",
                style = MaterialTheme.typography.labelSmall,
                color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
fun EndStateMessage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "the night is quiet",
            style = MaterialTheme.typography.bodyMedium,
            color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "you've heard all the voices for now",
            style = MaterialTheme.typography.labelSmall,
            color = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.4f)
        )
    }
}
