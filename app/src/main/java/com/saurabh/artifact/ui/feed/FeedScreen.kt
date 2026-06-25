package com.saurabh.artifact.ui.feed

import android.util.Log
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import androidx.compose.ui.zIndex
import com.saurabh.artifact.ui.components.ArtifactCard
import com.saurabh.artifact.ui.components.ArtifactFeedCard
import com.saurabh.artifact.ui.components.AmbientUploadBar
import com.saurabh.artifact.ui.components.EmberLogo
import com.saurabh.artifact.ui.components.CrisisSupportCard
import com.saurabh.artifact.ui.components.EmotionList
import com.saurabh.artifact.ui.components.state.EmptyFeedState
import com.saurabh.artifact.ui.components.ReflectionPromptCard
import androidx.compose.foundation.lazy.rememberLazyListState
import com.saurabh.artifact.ui.components.motion.FadeInContent
import com.saurabh.artifact.ui.components.state.LoadingPlaceholder
import com.saurabh.artifact.ui.theme.Spacing
import com.saurabh.artifact.ui.components.recording.AuraDock
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.ui.components.PetalChip
import com.saurabh.artifact.ui.components.QuietTab
import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.util.StartupTracer
import com.saurabh.artifact.ui.components.BrandTitle
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.FeedArtifact
import com.saurabh.artifact.model.FeedDisplayItem
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.model.FeedbackType
import com.saurabh.artifact.BuildConfig
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.paging.LoadState
import kotlin.time.Duration.Companion.milliseconds


@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    onNavigateToRecord: (String?) -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToDebugMenu: () -> Unit,
    onReportArtifact: (String) -> Unit,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    Log.d("APP_FLOW", "Composition: FeedScreen Entered")
    
    val recentArtifacts = viewModel.artifacts.collectAsLazyPagingItems()
    val forYouArtifacts = viewModel.personalizedArtifacts.collectAsLazyPagingItems()
    val unfinished by viewModel.unfinishedArtifacts.collectAsStateWithLifecycle()
    
    val isRankedLoading by viewModel.isRankedLoading.collectAsStateWithLifecycle()
    val stage by viewModel.startupStage.collectAsStateWithLifecycle()
    
    val selectedEmotion by viewModel.selectedEmotion.collectAsStateWithLifecycle()
    val publishState by viewModel.currentPublishState.collectAsStateWithLifecycle()

    val reflectionPrompt by viewModel.reflectionPrompt.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val hasNewContent by viewModel.hasNewContent.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // New Viewport-aware Hydration Logic
    ViewportCoordinator(state = listState, viewModel = viewModel)

    LaunchedEffect(Unit) {
        // Wait for first frame and navigation to settle
        withFrameNanos { }
        delay(100.milliseconds) // Reduced from 300ms for faster hydration
        Log.d("APP_FLOW", "FeedScreen: viewModel.start() triggered")
        StartupTracer.mark("FeedScreen: viewModel.start() triggered")
        viewModel.start()
    }

    var showRankedFeed by remember { mutableStateOf(value = true) }

    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    LaunchedEffect(error) {
        error?.let { uiError ->
            val result = snackbarHostState.showSnackbar(
                message = uiError.message.asString(context),
                actionLabel = uiError.actionLabel?.asString(context),
                duration = if (uiError.actionLabel != null) SnackbarDuration.Indefinite else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                uiError.onAction?.invoke()
            }
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            FeedTopBar(
                viewModel = viewModel,
                onNavigateToNotifications = onNavigateToNotifications,
                onNavigateToProfile = onNavigateToProfile,
                onNavigateToDebugMenu = onNavigateToDebugMenu
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

            AmbientUploadBar(
                state = publishState,
                onRetry = { viewModel.retryPublish(it) },
                onCancel = { viewModel.cancelPublish(it) }
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
                        forYouArtifacts = forYouArtifacts,
                        recentArtifacts = recentArtifacts,
                        unfinished = unfinished,
                        listState = listState,
                        viewModel = viewModel,
                        reflectionPrompt = reflectionPrompt,
                        stage = stage,
                        onNavigateToRecord = onNavigateToRecord,
                        onReportClick = onReportArtifact
                    )
                }

                // Floating New Content Indicator - Moved outside PullToRefreshBox to avoid ColumnScope issues
                NewContentOverlay(
                    visible = hasNewContent && !isRefreshing,
                    onRefresh = { viewModel.refreshFeed() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .zIndex(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FeedTopBar(
    viewModel: FeedViewModel,
    onNavigateToNotifications: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToDebugMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentUser = ArtifactTheme.currentUser

    CenterAlignedTopAppBar(
        title = {
            BrandTitle(
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .alpha(0.8f)
                    .then(
                        if (BuildConfig.DEBUG) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = onNavigateToDebugMenu
                            )
                        } else {
                            Modifier
                        }
                    )
            )
        },
        actions = {
            val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()

            IconButton(onClick = onNavigateToNotifications) {
                BadgedBox(
                    badge = {
                        if (unreadCount > 0) {
                            Badge(
                                containerColor = Color(0xFFFFB84D), // Warm Amber
                                modifier = Modifier.size(6.dp)
                            )
                        }
                    }
                ) {
                    Icon(
                        Icons.Rounded.Notifications,
                        contentDescription = if (unreadCount > 0)
                            "Echoes, new activity available"
                        else "Echoes"
                    )
                }
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
        ),
        modifier = modifier
    )
}

@Composable
private fun FeedVibeHeader(
    selectedEmotion: String?,
    showRankedFeed: Boolean,
    onToggleFeed: (Boolean) -> Unit,
    onEmotionSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(bottom = 8.dp)) {
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
            // Intentionally using default keys because this is a static,
            // immutable list of emotions with fixed ordering.
            items(EmotionList) { emotion ->
                val displayLabel = if (selectedEmotion == emotion.label) {
                    when (emotion.label) {
                        "Sad" -> "Sad & Lonely"
                        "Happy" -> "Happy & Hopeful"
                        "Anxious" -> "Anxious & Angry"
                        "Neutral" -> "Neutral & Calm"
                        else -> emotion.label
                    }
                } else {
                    emotion.label
                }
                PetalChip(
                    label = displayLabel,
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
    forYouArtifacts: androidx.paging.compose.LazyPagingItems<FeedDisplayItem>,
    recentArtifacts: androidx.paging.compose.LazyPagingItems<FeedDisplayItem>,
    unfinished: List<FeedArtifact>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: FeedViewModel,
    reflectionPrompt: ReflectionPrompt?,
    stage: StartupStage,
    onNavigateToRecord: (String?) -> Unit,
    onReportClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentArtifacts = if (showRankedFeed) forYouArtifacts else recentArtifacts
    val isEmpty = currentArtifacts.itemCount == 0
    val isRefreshing = currentArtifacts.loadState.refresh is LoadState.Loading

    FadeInContent(
        visible = !isRankedLoading || !isEmpty,
        modifier = modifier
    ) {
        if (isEmpty) {
            if (isRefreshing || isRankedLoading) {
                FeedLoadingState()
            } else {
                EmptyFeedState(onRecordClick = { onNavigateToRecord(null) })
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 120.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item(key = "header") {
                    FeedHeader(viewModel, reflectionPrompt, stage, onNavigateToRecord)
                }

                if (showRankedFeed) {
                    // Inject unfinished sessions at the top of Ranked feed
                    if (unfinished.isNotEmpty()) {
                        item(key = "unfinished_section") {
                            Text(
                                "Continue Listening",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                        items(unfinished, key = { "unf_${it.artifact.id}" }) { item ->
                            ArtifactItem(
                                artifactId = item.artifact.id,
                                viewModel = viewModel,
                                onReportClick = onReportClick,
                                feedArtifact = item
                            )
                        }
                        item(key = "divider_unf") {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        }
                    }
                }

                items(
                    count = currentArtifacts.itemCount,
                    key = currentArtifacts.itemKey { it.id }
                ) { index ->
                    val item = currentArtifacts[index]
                    when (item) {
                        is FeedDisplayItem.ArtifactItem -> {
                            ArtifactItem(
                                artifactId = item.artifact.id,
                                viewModel = viewModel,
                                onReportClick = onReportClick
                            )
                        }
                        is FeedDisplayItem.BreakItem -> {
                            BreathBreakItem()
                        }
                        null -> { /* Paging placeholder */ }
                    }
                }

                if (currentArtifacts.loadState.append is LoadState.Loading) {
                    item(key = "loading_indicator") { LoadingIndicator() }
                }
                
                if (currentArtifacts.itemCount > 0) {
                    item(key = "drawn_signal") {
                        val context = LocalContext.current
                        LaunchedEffect(Unit) {
                            (context as? ComponentActivity)?.reportFullyDrawn()
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
    reflectionPrompt: ReflectionPrompt?, 
    stage: StartupStage,
    onNavigateToRecord: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val isPromptLoading by viewModel.isPromptLoading.collectAsStateWithLifecycle()
    val safetyLevel by viewModel.safetyLevel.collectAsStateWithLifecycle()
    val isCrisis by viewModel.isCrisis.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isCrisis) {
            CrisisSupportCard(
                onCallHelp = { 
                    try {
                        val intent = Intent(Intent.ACTION_DIAL).apply {
                            data = "tel:988".toUri()
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("FeedScreen", "Failed to launch dialer", e)
                    }
                },
                onContinue = { viewModel.dismissCrisis() }
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
    onReportClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    feedArtifact: FeedArtifact? = null
) {
    // Isolated State Collection: This item ONLY recomposes when its specific artifact data or status changes
    val artifact by remember(viewModel, artifactId) {
        viewModel.getArtifactFlow(artifactId)
    }.collectAsStateWithLifecycle(initialValue = null)

    val reason by remember(viewModel, artifactId) {
        viewModel.getRecommendationReason(artifactId)
    }.collectAsStateWithLifecycle(initialValue = null)

    val playingArtifact by viewModel.currentlyPlayingArtifact.collectAsStateWithLifecycle()
    val isPlayingGlobal by viewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by viewModel.audioPlayer.isBuffering.collectAsStateWithLifecycle()
    val hydrationLevel by remember(viewModel, artifactId) {
        viewModel.uiState.map { it.hydrationLevels[artifactId] ?: HydrationLevel.SHELL }
    }.collectAsStateWithLifecycle(initialValue = HydrationLevel.SHELL)
    
    val isCurrent = playingArtifact?.id == artifactId
    val isPlaying = isPlayingGlobal && isCurrent
    val isCurrentBuffering = isBuffering && isCurrent
    
    val effectiveArtifact = artifact ?: feedArtifact?.artifact
    
    effectiveArtifact?.let { art ->
        LaunchedEffect(Unit) {
            StartupMetrics.onFirstArtifactRendered()
        }

        val displayFeedArtifact = feedArtifact ?: FeedArtifact(
            artifact = art,
            reason = reason ?: com.saurabh.artifact.model.FeedRecommendationReason.DISCOVERY
        )

        // Show labels ONLY for high-value reasons (Resonance, Unfinished)
        // Suppress DISCOVERY labels to reduce UI clutter
        val shouldShowLabel = when {
            feedArtifact != null -> true // Unfinished sessions always show labels
            reason != null && reason != com.saurabh.artifact.model.FeedRecommendationReason.DISCOVERY -> true
            else -> false
        }

        // Use ArtifactFeedCard if we have a specific reason (not default discovery) or it's an unfinished item
        if (shouldShowLabel) {
            ArtifactFeedCard(
                feedArtifact = displayFeedArtifact,
                isPlaying = isPlaying,
                isBuffering = isCurrentBuffering,
                hydrationLevel = hydrationLevel,
                onPlayClick = { 
                    viewModel.playAudio(art) 
                    viewModel.onArtifactFocused(artifactId)
                },
                onReportClick = { onReportClick(artifactId) },
                onDeleteClick = { viewModel.deleteArtifact(artifactId) },
                onFeedbackClick = { viewModel.submitFeedback(artifactId, FeedbackType.NOT_FOR_ME) },
                onSettingsClick = { viewModel.showSettingsComingSoon() },
                currentUserId = viewModel.currentUserId,
                modifier = modifier
            )
        } else {
            ArtifactCard(
                artifact = art,
                isPlaying = isPlaying,
                isBuffering = isCurrentBuffering,
                hydrationLevel = hydrationLevel,
                onPlayClick = { 
                    viewModel.playAudio(art) 
                    viewModel.onArtifactFocused(artifactId)
                },
                onReportClick = { onReportClick(artifactId) },
                onDeleteClick = { viewModel.deleteArtifact(artifactId) },
                onFeedbackClick = { viewModel.submitFeedback(artifactId, FeedbackType.NOT_FOR_ME) },
                onSettingsClick = { viewModel.showSettingsComingSoon() },
                currentUserId = viewModel.currentUserId,
                modifier = modifier
            )
        }
    }
}

@Composable
fun BreathBreakItem(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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
private fun NewContentOverlay(
    visible: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier
    ) {
        NewContentIndicator(onClick = onRefresh)
    }
}

@Composable
private fun NewContentIndicator(
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(0xFFFFB84D), // Warm Amber
        contentColor = Color.White,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.height(40.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "New Reflections",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }
    }
}

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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
