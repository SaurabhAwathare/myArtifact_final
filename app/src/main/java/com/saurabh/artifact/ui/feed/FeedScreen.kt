package com.saurabh.artifact.ui.feed

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import com.saurabh.artifact.ui.components.ArtifactCard
import com.saurabh.artifact.ui.components.BottomPlayer
import com.saurabh.artifact.ui.components.EmberLogo
import com.saurabh.artifact.ui.components.CrisisSupportCard
import com.saurabh.artifact.ui.components.EmotionList
import com.saurabh.artifact.ui.components.state.EmptyFeedState
import com.saurabh.artifact.ui.components.ReflectionPromptCard
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import com.saurabh.artifact.ui.components.motion.FadeInContent
import com.saurabh.artifact.ui.components.state.LoadingPlaceholder
import com.saurabh.artifact.ui.components.moderation.ReportSheet
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.ui.components.recording.AuraDock
import com.saurabh.artifact.ui.components.recording.ActiveRecordingIndicator
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.ui.components.PetalChip
import com.saurabh.artifact.ui.components.QuietTab
import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.util.StartupTracer
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun FeedScreen(
    onNavigateToRecord: (String?) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    viewModel: FeedViewModel = hiltViewModel()
) {
    Log.d("APP_FLOW", "Composition: FeedScreen Entered")
    
    val artifacts = viewModel.artifacts.collectAsLazyPagingItems()
    val rankedArtifactIds by viewModel.rankedArtifactIds.collectAsStateWithLifecycle()
    val isRankedLoading by viewModel.isRankedLoading.collectAsStateWithLifecycle()
    val stage by viewModel.startupStage.collectAsStateWithLifecycle()
    
    val selectedEmotion by viewModel.selectedEmotion.collectAsStateWithLifecycle()

    val reflectionPrompt by viewModel.reflectionPrompt.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // New Viewport-aware Hydration Logic
    ViewportCoordinator(state = listState, viewModel = viewModel)

    LaunchedEffect(Unit) {
        // Wait for first frame and navigation to settle
        withFrameNanos { }
        delay(100) // Reduced from 300ms for faster hydration
        Log.d("APP_FLOW", "FeedScreen: viewModel.start() triggered")
        StartupTracer.mark("FeedScreen: viewModel.start() triggered")
        viewModel.start()
    }

    var showRankedFeed by remember { mutableStateOf(true) }
    var reportingArtifactId by remember { mutableStateOf<String?>(null) }

    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Retry",
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            FeedTopBar(
                onNavigateToNotifications = onNavigateToNotifications,
                onNavigateToProfile = onNavigateToProfile
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val isPlayerActive by viewModel.currentlyPlayingArtifact.collectAsStateWithLifecycle()
            AuraDock(
                onInitiate = { onNavigateToRecord(null) },
                modifier = Modifier.padding(bottom = if (isPlayerActive != null) 90.dp else 16.dp)
            )
        },
        floatingActionButtonPosition = FabPosition.End
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            FeedVibeHeader(
                selectedEmotion = selectedEmotion,
                showRankedFeed = showRankedFeed,
                onToggleFeed = { showRankedFeed = it },
                onEmotionSelect = { viewModel.setEmotionFilter(it) }
            )

            Box(modifier = Modifier.weight(1f)) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshFeed() },
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = rememberPullToRefreshState(),
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                            containerColor = MaterialTheme.colorScheme.surface,
                            color = Color(0xFFFFB84D) // Warm Amber
                        )
                    }
                ) {
                    FeedContent(
                        showRankedFeed = showRankedFeed,
                        isRankedLoading = isRankedLoading,
                        rankedArtifactIds = rankedArtifactIds,
                        artifacts = artifacts,
                        listState = listState,
                        viewModel = viewModel,
                        reflectionPrompt = reflectionPrompt,
                        stage = stage,
                        onNavigateToRecord = onNavigateToRecord,
                        onReportClick = { reportingArtifactId = it }
                    )
                }

                if (reportingArtifactId != null) {
                    ReportSheet(
                        onReportSubmitted = { reason, details ->
                            reportingArtifactId?.let { id ->
                                viewModel.reportArtifact(id, reason, details)
                            }
                            reportingArtifactId = null
                        },
                        onDismiss = { reportingArtifactId = null }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedTopBar(
    onNavigateToNotifications: () -> Unit,
    onNavigateToProfile: () -> Unit
) {
    val currentUser = ArtifactTheme.currentUser

    CenterAlignedTopAppBar(
        title = {
            com.saurabh.artifact.ui.components.BrandTitle(
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.alpha(0.8f)
            )
        },
        actions = {
            IconButton(onClick = onNavigateToNotifications) {
                Icon(Icons.Rounded.Notifications, contentDescription = "Echoes")
            }
            IconButton(onClick = onNavigateToProfile) {
                if (currentUser != null) {
                    ArtifactAvatar(
                        config = currentUser.avatarConfig,
                        size = 32.dp,
                        isStatic = true
                    )
                } else {
                    Icon(Icons.Rounded.Person, contentDescription = "Inner Space")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
    )
}

@Composable
private fun FeedVibeHeader(
    selectedEmotion: String?,
    showRankedFeed: Boolean,
    onToggleFeed: (Boolean) -> Unit,
    onEmotionSelect: (String?) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuietTab(
                text = "For You",
                selected = showRankedFeed,
                onClick = { onToggleFeed(true) }
            )
            Spacer(Modifier.width(16.dp))
            QuietTab(
                text = "Recent",
                selected = !showRankedFeed,
                onClick = { onToggleFeed(false) }
            )
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PetalChip(
                    label = "All",
                    selected = selectedEmotion == null,
                    onClick = { onEmotionSelect(null) }
                )
            }
            items(EmotionList) { emotion ->
                PetalChip(
                    label = emotion.label,
                    emoji = emotion.emoji,
                    selected = selectedEmotion == emotion.label,
                    onClick = { onEmotionSelect(emotion.label) }
                )
            }
        }
    }
}

@Composable
private fun FeedContent(
    showRankedFeed: Boolean,
    isRankedLoading: Boolean,
    rankedArtifactIds: List<String>,
    artifacts: androidx.paging.compose.LazyPagingItems<com.saurabh.artifact.model.Artifact>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: FeedViewModel,
    reflectionPrompt: com.saurabh.artifact.model.ReflectionPrompt?,
    stage: StartupStage,
    onNavigateToRecord: (String?) -> Unit,
    onReportClick: (String) -> Unit
) {
    val isEmpty = artifacts.itemCount == 0
    val isRefreshing = artifacts.loadState.refresh is androidx.paging.LoadState.Loading

    FadeInContent(visible = (!isRankedLoading || (showRankedFeed && rankedArtifactIds.isNotEmpty()) || (!showRankedFeed && !isEmpty))) {
        if (showRankedFeed) {
            if (isRankedLoading && rankedArtifactIds.isEmpty()) {
                FeedLoadingState()
            } else if (rankedArtifactIds.isEmpty()) {
                EmptyFeedState(onRecordClick = { onNavigateToRecord(null) })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 120.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    item(key = "header_ranked") {
                        FeedHeader(viewModel, reflectionPrompt, stage, onNavigateToRecord)
                    }

                    items(rankedArtifactIds, key = { it }) { artifactId ->
                        val itemIndex = rankedArtifactIds.indexOf(artifactId)
                        
                        ArtifactItem(
                            artifactId = artifactId,
                            viewModel = viewModel,
                            onReportClick = onReportClick
                        )
                        
                        if (itemIndex > 0 && (itemIndex + 1) % 4 == 0) {
                            this@LazyColumn.BreathBreakItem(itemIndex)
                        }
                    }
                    
                    if (rankedArtifactIds.isNotEmpty()) {
                        item(key = "drawn_signal_ranked") {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            LaunchedEffect(Unit) {
                                (context as? androidx.activity.ComponentActivity)?.reportFullyDrawn()
                            }
                        }
                    }
                }
            }
        } else {
            if (isEmpty && !isRefreshing) {
                EmptyFeedState(onRecordClick = { onNavigateToRecord(null) })
            } else if (isEmpty && isRefreshing) {
                FeedLoadingState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 120.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    item(key = "header_recent") {
                        FeedHeader(viewModel, reflectionPrompt, stage, onNavigateToRecord)
                    }

                    items(
                        count = artifacts.itemCount,
                        key = artifacts.itemKey { it.id }
                    ) { index ->
                        val artifact = artifacts[index]
                        if (artifact != null) {
                            ArtifactItem(
                                artifactId = artifact.id,
                                viewModel = viewModel,
                                onReportClick = onReportClick
                            )
                            
                            if ((index + 1) % 5 == 0) {
                                this@LazyColumn.BreathBreakItem(index)
                            }
                        }
                    }

                    if (artifacts.loadState.append is androidx.paging.LoadState.Loading) {
                        item(key = "loading_indicator") { LoadingIndicator() }
                    }
                    
                    if (artifacts.itemCount > 0) {
                        item(key = "drawn_signal_recent") {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            LaunchedEffect(Unit) {
                                (context as? androidx.activity.ComponentActivity)?.reportFullyDrawn()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FeedHeader(
    viewModel: FeedViewModel, 
    reflectionPrompt: com.saurabh.artifact.model.ReflectionPrompt?, 
    stage: StartupStage,
    onNavigateToRecord: (String?) -> Unit
) {
    val isPromptLoading by viewModel.isPromptLoading.collectAsStateWithLifecycle()
    val safetyLevel by viewModel.safetyLevel.collectAsStateWithLifecycle()
    val isCrisis by viewModel.isCrisis.collectAsStateWithLifecycle()
    
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (isCrisis) {
            CrisisSupportCard(
                onCallHelp = { /* Intent to dialer */ },
                onContinue = { /* Dismiss or continue */ }
            )
        }

        // Defer heavy prompt card rendering until IMMERSION stage
        FadeInContent(visible = stage >= StartupStage.IMMERSION) {
            ReflectionPromptCard(
                prompt = reflectionPrompt,
                isLoading = isPromptLoading,
                safetyLevel = safetyLevel,
                onUse = { onNavigateToRecord(it) },
                onRefresh = { viewModel.refreshReflectionPrompt() }
            )
        }
    }
}

/**
 * Optimized ArtifactItem that isolates state observation to the item level.
 */
@Composable
fun ArtifactItem(
    artifactId: String,
    viewModel: FeedViewModel,
    onReportClick: (String) -> Unit
) {
    // Isolated State Collection: This item ONLY recompiles when its specific artifact data or status changes
    val artifact by remember(viewModel, artifactId) {
        viewModel.getArtifactFlow(artifactId)
    }.collectAsStateWithLifecycle(initialValue = null)

    val playingArtifact by viewModel.currentlyPlayingArtifact.collectAsStateWithLifecycle()
    val isPlayingGlobal by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.audioPlayer.isBuffering.collectAsStateWithLifecycle()
    
    val isCurrent = playingArtifact?.id == artifactId
    val isPlaying = isPlayingGlobal && isCurrent
    val isCurrentBuffering = isBuffering && isCurrent
    
    artifact?.let { 
        LaunchedEffect(Unit) {
            StartupMetrics.onFirstArtifactRendered()
        }
        ArtifactCard(
            artifact = it,
            isPlaying = isPlaying,
            isBuffering = isCurrentBuffering,
            onPlayClick = { 
                viewModel.playAudio(it) 
                viewModel.onArtifactFocused(artifactId)
            },
            onReportClick = { onReportClick(artifactId) },
            currentUserId = viewModel.currentUserId
        )
    }
}

fun LazyListScope.BreathBreakItem(index: Int) {
    item(key = "break_$index") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmberLogo(size = 12.dp, isPulsing = true)
                Spacer(Modifier.height(12.dp))
                Text(
                    "take a breath",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
fun FeedLoadingState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                    .padding(Spacing.Large)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                        LoadingPlaceholder(height = 40.dp, width = Modifier.size(40.dp))
                        LoadingPlaceholder(height = 20.dp, width = Modifier.width(120.dp))
                    }
                    LoadingPlaceholder(height = 20.dp)
                    LoadingPlaceholder(height = 20.dp, width = Modifier.fillMaxWidth(0.6f))
                }
            }
        }
    }
}

@Composable
fun LoadingIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
    }
}
