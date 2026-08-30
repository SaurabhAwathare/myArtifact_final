package com.saurabh.artifact.ui.feed

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import androidx.compose.ui.zIndex
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.ui.feed.components.AtmosphereHeader
import com.saurabh.artifact.ui.components.ArtifactCard
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
import com.saurabh.artifact.ui.components.AppSnackbarHost
import com.saurabh.artifact.ui.util.LocalBottomOverlayOffset
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.ui.components.PetalChip
import com.saurabh.artifact.ui.components.QuietTab
import com.saurabh.artifact.ui.components.ArtifactSigil
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500
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
    onAuthorClick: (String) -> Unit = {},
    onNavigateToSecurity: () -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val logger = ArtifactTheme.logger
    logger.trace(DiagnosticCategory.FEED, "FEED_SCREEN_COMPOSITION")
    
    val recentArtifacts = viewModel.artifacts.collectAsLazyPagingItems()
    val forYouArtifacts = viewModel.personalizedArtifacts.collectAsLazyPagingItems()
    
    val isRankedLoading by viewModel.isRankedLoading.collectAsStateWithLifecycle()
    val stage by viewModel.startupStage.collectAsStateWithLifecycle()
    
    val selectedEmotion by viewModel.selectedEmotion.collectAsStateWithLifecycle()
    val showRankedFeed by viewModel.showRankedFeed.collectAsStateWithLifecycle()
    val publishState by viewModel.currentPublishState.collectAsStateWithLifecycle()

    val reflectionPrompt by viewModel.reflectionPrompt.collectAsStateWithLifecycle()
    val atmosphereStatement by viewModel.atmosphereStatement.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val hasNewContent by viewModel.hasNewContent.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // New Viewport-aware Hydration Logic
    ViewportCoordinator(state = listState, viewModel = viewModel)

    LaunchedEffect(Unit) {
        // Wait for first frame and navigation to settle
        withFrameNanos { }
        delay(100.milliseconds) // Reduced from 300ms for faster hydration
        logger.debug(DiagnosticCategory.FEED, "FEED_START_TRIGGERED")
        StartupTracer.mark("FeedScreen: viewModel.start() triggered")
        viewModel.start()
    }

    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    LaunchedEffect(error) {
        error?.let { uiError ->
            val result = snackbarHostState.showSnackbar(
                message = uiError.message.asString(context),
                actionLabel = uiError.actionLabel?.asString(context),
                duration = if (uiError.actionLabel != null) SnackbarDuration.Indefinite else SnackbarDuration.Short,
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
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            AuraDock(
                onInitiate = { onNavigateToRecord(null) },
                modifier = Modifier.padding(bottom = 16.dp)
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
                onToggleFeed = { viewModel.setShowRankedFeed(it) },
                onEmotionSelect = { viewModel.setEmotionFilter(it) }
            )

            AmbientUploadBar(
                state = publishState,
                onRetry = { viewModel.retryPublish(it) }
            ) { viewModel.cancelPublish() }

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
                        listState = listState,
                        viewModel = viewModel,
                        reflectionPrompt = reflectionPrompt,
                        atmosphereStatement = atmosphereStatement,
                        stage = stage,
                        onNavigateToRecord = onNavigateToRecord,
                        onReportClick = onReportArtifact,
                        onAuthorClick = onAuthorClick,
                        onNavigateToSecurity = onNavigateToSecurity
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
                            "New activity available"
                        else "Notifications"
                    )
                }
            }
            IconButton(onClick = onNavigateToProfile) {
                if (currentUser != null) {
                    ArtifactSigil(
                        config = currentUser.sigilConfig,
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
    listState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: FeedViewModel,
    reflectionPrompt: ReflectionPrompt?,
    atmosphereStatement: String?,
    stage: StartupStage,
    onNavigateToRecord: (String?) -> Unit,
    onReportClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onNavigateToSecurity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentArtifacts = if (showRankedFeed) forYouArtifacts else recentArtifacts
    val isEmpty = currentArtifacts.itemCount == 0
    val isRefreshing = currentArtifacts.loadState.refresh is LoadState.Loading

    Box(modifier = modifier) {
        // ALWAYS keep LazyColumn in composition tree to preserve scroll restoration anchor.
        // Even if empty, the Paging library will soon populate it from Room if SKIP_INITIAL_REFRESH was used.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 120.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item(key = "header") {
                FeedHeader(
                    viewModel = viewModel, 
                    reflectionPrompt = reflectionPrompt, 
                    stage = stage, 
                    onNavigateToRecord = onNavigateToRecord, 
                    atmosphereStatement = atmosphereStatement,
                    onNavigateToSecurity = onNavigateToSecurity
                )
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
                            onReportClick = onReportClick,
                            onAuthorClick = onAuthorClick
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

        // Overlay loading or empty states when the paging items are empty.
        // We use a background to mask the empty LazyColumn (which still contains the header).
        if (isEmpty) {
            FadeInContent(
                visible = isRankedLoading || isRefreshing || isEmpty,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            ) {
                if (isRefreshing || isRankedLoading) {
                    FeedLoadingState(modifier = Modifier.fillMaxSize())
                } else {
                    EmptyFeedState(onRecordClick = { onNavigateToRecord(null) })
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
    atmosphereStatement: String?,
    modifier: Modifier = Modifier,
    onNavigateToSecurity: () -> Unit = {}
) {
    val logger = ArtifactTheme.logger
    val isPromptLoading by viewModel.isPromptLoading.collectAsStateWithLifecycle()
    val safetyLevel by viewModel.safetyLevel.collectAsStateWithLifecycle()
    val isCrisis by viewModel.isCrisis.collectAsStateWithLifecycle()
    val feedState by viewModel.uiState.collectAsStateWithLifecycle()
    val isMnemonicSaved = feedState.isMnemonicSaved
    val context = LocalContext.current
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (atmosphereStatement != null) {
            AtmosphereHeader(statement = atmosphereStatement)
        }

        if (isCrisis) {
            CrisisSupportCard(
                onCallHelp = { 
                    try {
                        val intent = Intent(Intent.ACTION_DIAL).apply {
                            data = "tel:988".toUri()
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        logger.error(DiagnosticCategory.FEED, "DIALER_LAUNCH_FAILED", throwable = e)
                    }
                },
                onContinue = { viewModel.dismissCrisis() }
            )
        }

        if (!isMnemonicSaved && (stage >= StartupStage.IMMERSION)) {
            SecurityNudgeCard(onClick = onNavigateToSecurity)
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

@Composable
fun ArtifactItem(
    artifactId: String,
    viewModel: FeedViewModel,
    onReportClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    feedArtifact: FeedArtifact? = null
) {
    // Isolated State Collection: This item ONLY recomposes when its specific artifact data or status changes
    val artifact by remember(viewModel, artifactId) {
        viewModel.getArtifactFlow(artifactId)
    }.collectAsStateWithLifecycle(initialValue = null)

    val artifactDetail by remember(viewModel, artifactId) {
        viewModel.getArtifactDetailFlow(artifactId)
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
        val effectiveReason = reason ?: feedArtifact?.reason ?: com.saurabh.artifact.model.FeedRecommendationReason.DISCOVERY

        LaunchedEffect(Unit) {
            StartupMetrics.onFirstArtifactRendered()
            viewModel.recordImpression(art.id, effectiveReason)
        }

        ArtifactCard(
            artifact = art,
            isPlaying = isPlaying,
            isBuffering = isCurrentBuffering,
            hydrationLevel = hydrationLevel,
            onPlayClick = { 
                viewModel.playAudio(art, effectiveReason) 
                viewModel.onArtifactFocused(artifactId)
            },
            onReportClick = { onReportClick(artifactId) },
            onFeedbackClick = { viewModel.submitFeedback(artifactId, FeedbackType.NOT_FOR_ME) },
            onSettingsClick = { viewModel.showSettingsComingSoon() },
            onAuthorClick = onAuthorClick,
            currentUserId = viewModel.currentUserId,
            artifactDetail = artifactDetail,
            recommendationReason = effectiveReason,
            modifier = modifier
        )
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
fun FeedLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(20.dp),
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
                "New Artifacts",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }
    }
}

@Composable
fun SecurityNudgeCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ArtifactTheme.colors.surfaceHearth),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = GoldAura500,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Secure Your Journey",
                    style = MaterialTheme.typography.titleSmall,
                    color = ArtifactTheme.colors.onSurfaceMain,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Set up your recovery phrase to ensure you never lose your artifacts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArtifactTheme.colors.onSurfaceMuted
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = ArtifactTheme.colors.onSurfaceMuted
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
