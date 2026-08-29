package com.saurabh.artifact.ui.feed

import androidx.collection.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PublishStateManager
import com.saurabh.artifact.domain.feed.GetFeedFlowUseCase
import com.saurabh.artifact.domain.feed.GetPersonalizedFeedFlowUseCase
import com.saurabh.artifact.domain.prompt.GetReflectionPromptUseCase
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.ArtifactEngagementRepository
import com.saurabh.artifact.repository.ModerationEvent
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.NotificationRepository
import com.saurabh.artifact.repository.SavedArtifactManager
import com.saurabh.artifact.repository.CommunityRepository
import com.saurabh.artifact.service.AdManager
import com.saurabh.artifact.service.FeedComposer
import com.saurabh.artifact.service.PersonalizationEngine
import com.saurabh.artifact.service.SafetyLevel
import com.saurabh.artifact.service.FeedSeparatorMapper
import com.saurabh.artifact.security.UploadGuard
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.util.MemoryManager
import com.saurabh.artifact.util.MemoryTrimable
import com.saurabh.artifact.util.StartupTracer
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.UiError
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.ui.util.AtmosphereMapper
import com.saurabh.artifact.R
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HydrationLevel {
    SHELL,      // Static frame, default fonts, no animations
    METADATA,   // Adds reactions, counts, and tags
    ENRICHED,   // Adds play button, static waveform
    FULL        // Interactive waveform, atmospheric effects
}

data class FeedUiState(
    val artifactCache: Map<String, Artifact> = emptyMap(),
    val hydrationLevels: Map<String, HydrationLevel> = emptyMap(),
    val artifactDetails: Map<String, ArtifactDetail> = emptyMap(),
    val recommendationReasons: Map<String, FeedRecommendationReason> = emptyMap(),
    val isRankedLoading: Boolean = false,
    val selectedEmotion: String? = null,
    val showRankedFeed: Boolean = true,
    val reflectionPrompt: ReflectionPrompt? = null,
    val isPromptLoading: Boolean = false,
    val safetyLevel: SafetyLevel = SafetyLevel.LOW,
    val isCrisis: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasNewContent: Boolean = false,
    val isMnemonicSaved: Boolean = true,
    val atmosphereStatement: String? = null,
    val error: UiError? = null
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val artifactRepository: ArtifactRepository,
    private val artifactEngagementRepository: ArtifactEngagementRepository,
    private val authRepository: AuthRepository,
    private val notificationRepository: NotificationRepository,
    private val communityRepository: CommunityRepository,
    private val personalizationEngine: PersonalizationEngine,
    private val adManager: AdManager,
    private val memoryManager: MemoryManager,
    private val onboardingManager: com.saurabh.artifact.util.OnboardingManager,
    startupCoordinator: StartupCoordinator,
    savedArtifactManager: SavedArtifactManager,
    private val firestore: FirebaseFirestore,
    val audioPlayer: PlaybackCoordinator,
    private val publishStateManager: PublishStateManager,
    private val uploadGuard: UploadGuard,
    private val feedComposer: FeedComposer,
    private val feedSeparatorMapper: FeedSeparatorMapper,
    getFeedFlowUseCase: GetFeedFlowUseCase,
    getPersonalizedFeedFlowUseCase: GetPersonalizedFeedFlowUseCase,
    private val getReflectionPromptUseCase: GetReflectionPromptUseCase,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel(), MemoryTrimable {

    private companion object {
        const val KEY_SELECTED_EMOTION = "selected_emotion"
        const val KEY_SHOW_RANKED_FEED = "show_ranked_feed"
    }

    // Persisted state properties (Single Source of Truth)
    val selectedEmotion = savedStateHandle.getStateFlow<String?>(KEY_SELECTED_EMOTION, null)
    val showRankedFeed = savedStateHandle.getStateFlow(KEY_SHOW_RANKED_FEED, true)

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = combine(
        _uiState,
        selectedEmotion,
        showRankedFeed,
        onboardingManager.isMnemonicSaved
    ) { current, emotion, ranked, mnemonicSaved ->
        current.copy(
            selectedEmotion = emotion,
            showRankedFeed = ranked,
            isMnemonicSaved = mnemonicSaved
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FeedUiState())

    // LRU cache for artifact details to prevent unbounded memory growth
    private val detailsCache = object : LruCache<String, ArtifactDetail>(10) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: ArtifactDetail, newValue: ArtifactDetail?) {
            if (evicted) {
                _uiState.update { current ->
                    current.copy(artifactDetails = current.artifactDetails.toMutableMap().apply { remove(key) })
                }
            }
        }
    }

    val currentlyPlayingArtifact: StateFlow<Artifact?> = audioPlayer.currentArtifact
    val isPlaying = audioPlayer.isPlaying
    val currentPosition: Flow<Duration> = audioPlayer.currentPosition
    val duration: Flow<Duration> = audioPlayer.duration
    val startupStage = startupCoordinator.stage
    val currentUserId: String? get() = authRepository.currentUser.value?.uid

    // Awareness state derived from the notification stream
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val unreadCount: StateFlow<Int> = authRepository.currentUser
        .flatMapLatest { user ->
            if (user != null) {
                // Note: Pagination means this only reflects unread items in the live head
                notificationRepository.listenNotifications(user.uid, limit = 50)
                    .map { (items, _) -> items.count { !it.isRead } }
            } else {
                flowOf(0)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val currentPublishState: StateFlow<PublishState?> = publishStateManager.currentPublishState

    private val _refreshTrigger = MutableStateFlow(0)
    private var previousTopFiveIds: Set<String> = emptySet()
    private var currentPlaybackReason: FeedRecommendationReason? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val artifacts: Flow<PagingData<FeedDisplayItem>> = combine(
        selectedEmotion,
        _refreshTrigger
    ) { emotion, _ -> emotion }.flatMapLatest { emotion ->
        getFeedFlowUseCase(emotion)
    }.map { pagingData ->
        pagingData.map { item ->
            hydrateFromPaging(item.artifact)
            item
        }
    }.map { pagingData ->
        feedSeparatorMapper.mapToDisplayItems(pagingData)
    }.cachedIn(viewModelScope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val personalizedArtifacts: Flow<PagingData<FeedDisplayItem>> = combine(
        selectedEmotion,
        _refreshTrigger
    ) { emotion, _ -> emotion }.flatMapLatest { emotion ->
        getPersonalizedFeedFlowUseCase(emotion)
    }.map { pagingData ->
        pagingData.map { item ->
            hydrateFromPaging(item.artifact)
            item
        }
    }.map { pagingData ->
        feedSeparatorMapper.mapToDisplayItems(pagingData)
    }.cachedIn(viewModelScope)

    // Legacy compatibility accessors
    val isRankedLoading = uiState.map { it.isRankedLoading }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val reflectionPrompt = uiState.map { it.reflectionPrompt }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isPromptLoading = uiState.map { it.isPromptLoading }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val safetyLevel = uiState.map { it.safetyLevel }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SafetyLevel.LOW)
    val isCrisis = uiState.map { it.isCrisis }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isRefreshing = uiState.map { it.isRefreshing }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val hasNewContent = uiState.map { it.hasNewContent }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val atmosphereStatement = uiState.map { it.atmosphereStatement }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val error = uiState.map { it.error }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        memoryManager.register(this)
        start()

        // Listen for save events
        viewModelScope.launch {
            savedArtifactManager.events.collect { event ->
                val error = when (event) {
                    is SavedArtifactManager.SavedEvent.Success -> {
                        val text = if (event.isSaved) UiText.StringResource(R.string.saved_to_journey)
                        else UiText.StringResource(R.string.removed_from_journey)
                        UiError(text)
                    }
                    is SavedArtifactManager.SavedEvent.Failure -> {
                        UiError(UiText.StringResource(R.string.generic_error))
                    }
                }
                _uiState.update { it.copy(error = error) }
            }
        }

        // Listen for global moderation events (e.g. Report Success)
        viewModelScope.launch {
            artifactRepository.moderationRepository.get().events.collect { event ->
                when (event) {
                    is ModerationEvent.ReportSuccess -> {
                        _uiState.update { it.copy(
                            error = UiError(UiText.DynamicString("Artifact hidden from your feed."))
                        ) }
                        _refreshTrigger.value += 1
                        diagnosticLogger.info(DiagnosticCategory.FEED, "FEED_REFRESH_REPORT_SUCCESS", mapOf("artifactId" to event.artifactId))
                    }
                }
            }
        }
    }

    private val startupJob = kotlinx.coroutines.SupervisorJob()
    private var started = false

    fun start() {
        if (currentUserId == null) {
            diagnosticLogger.warn(DiagnosticCategory.FEED, "FEED_START_BLOCKED", mapOf("reason" to "User is null"))
            _uiState.update { it.copy(artifactCache = emptyMap(), hydrationLevels = emptyMap()) }
            return
        }
        if (started) return
        started = true

        diagnosticLogger.info(DiagnosticCategory.FEED, "FEED_HYDRATION_STARTED")
        StartupTracer.mark("Feed Hydration Started")
        StartupMetrics.onFeedHydrationStart()
        
        viewModelScope.launch {
            // PHASE 1: Critical UI Path (Primary Feed Content)
            launch(Dispatchers.Default) {
                runCatching { 
                    loadRankedFeed()
                    StartupTracer.mark("Ranked Feed Loaded")
                }.onFailure { diagnosticLogger.error(DiagnosticCategory.FEED, "FEED_RANKED_LOAD_FAILED", throwable = it) }
            }

            // PHASE 2: Contextual Enrichment (Prompts & Personalization Init)
            launch {
                delay(1500.milliseconds) 
                runCatching { 
                    refreshReflectionPrompt()
                    refreshAtmosphere()
                    StartupTracer.mark("Reflection Prompt Hydrated")
                }.onFailure { diagnosticLogger.error(DiagnosticCategory.FEED, "FEED_PROMPT_REFRESH_FAILED", throwable = it) }
            }

            // PHASE 3: Background & Deferred (Low priority syncs)
            launch {
                delay(4000.milliseconds) 
                personalizationEngine.ensureInitialized()
                artifactRepository.runCacheCleanup()
                StartupTracer.mark("Personalization Engine & Cache Cleanup Initialized")
            }
            
            launch { observePlaybackCompletion() }
            launch { observePlaybackProgress() }
            launch { startNewContentListener() }
        }
    }

    private var newContentListener: ListenerRegistration? = null
    private var lastLoadedTimestamp: Long = System.currentTimeMillis()

    private fun startNewContentListener() {
        newContentListener?.remove()

        newContentListener = firestore.collection("artifacts")
            .whereEqualTo("isPublic", true)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    diagnosticLogger.error(DiagnosticCategory.FEED, "NEW_CONTENT_LISTENER_ERROR", throwable = error)
                    return@addSnapshotListener
                }

                val latestDoc = snapshot?.documents?.firstOrNull() ?: return@addSnapshotListener
                val createdAt = latestDoc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                
                if (createdAt > lastLoadedTimestamp) {
                    _uiState.update { it.copy(hasNewContent = true) }
                }
            }
    }

    private fun observePlaybackProgress() {
        viewModelScope.launch {
            audioPlayer.currentProgress.collect { progress ->
                if (progress != null) {
                    val artifactId = progress.artifactId
                    
                    // Phase 10.2: DISCOVERY_DEPTH_YIELD
                    // Proxy: Log when an impression-eligible playback reaches validation (95%+)
                    val reason = currentPlaybackReason
                    if (reason != null && (reason == FeedRecommendationReason.EMOTIONAL_RESONANCE || reason == FeedRecommendationReason.DISCOVERY)) {
                        if (progress.isValidationMet) {
                            diagnosticLogger.info(
                                DiagnosticCategory.FEED, 
                                "DISCOVERY_DEPTH_YIELD", 
                                mapOf("artifactId" to artifactId, "milestone" to "COMPLETE", "reason" to reason.name)
                            )
                        }
                    }

                    if (progress.isValidationMet) {
                        diagnosticLogger.debug(DiagnosticCategory.FEED, "PLAYBACK_THRESHOLD_MET", mapOf("artifactId" to artifactId))
                    }
                }
            }
        }
    }

    private fun observePlaybackCompletion() {
        viewModelScope.launch {
            audioPlayer.playbackCompletedEvent.collectLatest { completedUrl ->
                val current = audioPlayer.currentArtifact.value
                if (current?.audioUrl == completedUrl) {
                    personalizationEngine.recordDetailedInteraction(
                        emotion = current.emotion,
                        completionRate = 1.0f
                    )
                    handlePostPlaybackAd()
                }
            }
        }
    }

    private fun handlePostPlaybackAd() {
        val state = _uiState.value
        if (state.isCrisis || state.safetyLevel == SafetyLevel.HIGH) return

        if (adManager.canPlayAudioAd()) {
            adManager.recordAdShown()
        }
    }

    fun refreshReflectionPrompt(context: String? = null): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            if (uiState.value.isPromptLoading) return@launch
            
            _uiState.update { it.copy(isPromptLoading = true) }
            runCatching {
                val result = getReflectionPromptUseCase(
                    emotion = selectedEmotion.value,
                    context = context
                )
                _uiState.update { it.copy(
                    safetyLevel = result.safetyLevel,
                    isCrisis = result.isCrisis,
                    reflectionPrompt = result.prompt
                ) }
            }.onFailure {
                diagnosticLogger.error(DiagnosticCategory.FEED, "PROMPT_REFRESH_FAILED", throwable = it)
            }
            _uiState.update { it.copy(isPromptLoading = false) }
        }
    }

    fun setEmotionFilter(emotion: String?) {
        savedStateHandle[KEY_SELECTED_EMOTION] = emotion
        loadRankedFeed()
    }

    fun setShowRankedFeed(showRanked: Boolean) {
        savedStateHandle[KEY_SHOW_RANKED_FEED] = showRanked
    }

    fun loadRankedFeed(): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            val userId = currentUserId ?: run {
                diagnosticLogger.warn(DiagnosticCategory.FEED, "FEED_LOAD_BLOCKED", mapOf("reason" to "UNAUTHENTICATED"))
                return@launch
            }
            diagnosticLogger.debug(DiagnosticCategory.FEED, "FEED_LOAD_STARTED", mapOf("type" to "RANKED"))
            _uiState.update { it.copy(isRankedLoading = true) }
            
            runCatching {
                val feedItems = withContext(Dispatchers.Default) {
                    feedComposer.composeFeed(userId)
                }

                // Phase 10.2: Discovery Health Telemetry
                val topFive = feedItems.take(5)
                if (topFive.isNotEmpty()) {
                    // 1. DISCOVERY_AGE_DISTRIBUTION
                    val nowMs = System.currentTimeMillis()
                    val avgAgeMs = topFive.map { nowMs - it.artifact.createdAt.toDate().time }.average()
                    val avgAgeHours = avgAgeMs / (1000 * 60 * 60)
                    diagnosticLogger.info(DiagnosticCategory.FEED, "DISCOVERY_AGE_DISTRIBUTION", mapOf("avgAgeHours" to avgAgeHours))

                    // 2. DISCOVERY_CHURN_RATE
                    val currentIds = topFive.map { it.artifact.id }.toSet()
                    if (previousTopFiveIds.isNotEmpty()) {
                        val newCount = currentIds.count { it !in previousTopFiveIds }
                        val churnRate = newCount.toFloat() / currentIds.size
                        diagnosticLogger.info(DiagnosticCategory.FEED, "DISCOVERY_CHURN_RATE", mapOf("churnRate" to churnRate))
                    }
                    previousTopFiveIds = currentIds
                }

                // Cache recommendation reasons for the UI
                val reasons = feedItems.associateBy({ it.artifact.id }, { it.reason })
                _uiState.update { it.copy(recommendationReasons = it.recommendationReasons + reasons) }

                diagnosticLogger.info(DiagnosticCategory.FEED, "FEED_LOAD_SUCCESS", mapOf("count" to feedItems.size))
            }.onFailure {
                diagnosticLogger.error(DiagnosticCategory.FEED, "FEED_LOAD_FAILED", throwable = it)
            }
            _uiState.update { it.copy(isRankedLoading = false) }
        }
    }

    fun refreshFeed() {
        diagnosticLogger.info(DiagnosticCategory.FEED, "FEED_REFRESH_TRIGGERED")
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, hasNewContent = false) }
            lastLoadedTimestamp = System.currentTimeMillis()
            
            val rankedJob = loadRankedFeed()
            val promptJob = refreshReflectionPrompt()
            val atmosphereJob = refreshAtmosphere()
            
            _refreshTrigger.value += 1
            
            rankedJob.join()
            promptJob.join()
            atmosphereJob.join()
            
            delay(500.milliseconds)
            
            _uiState.update { it.copy(isRefreshing = false) }
            diagnosticLogger.info(DiagnosticCategory.FEED, "FEED_REFRESH_SUCCESS")
        }
    }

    private fun refreshAtmosphere(): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            communityRepository.getLatestAtmosphere()
                .onSuccess { atmosphere ->
                    val statement = AtmosphereMapper.mapToStatement(atmosphere)
                    _uiState.update { it.copy(atmosphereStatement = statement) }
                }
                .onFailure { e ->
                    diagnosticLogger.warn(DiagnosticCategory.FEED, "ATMOSPHERE_FETCH_FAILED", throwable = e)
                    // We don't update state here to avoid showing an error for a non-critical feature
                }
        }
    }

    fun updateHydrationLevels(updates: Map<String, HydrationLevel>) {
        _uiState.update { it.copy(hydrationLevels = it.hydrationLevels + updates) }
    }

    fun getArtifactFlow(id: String): Flow<Artifact?> {
        return _uiState.map { it.artifactCache[id] }.distinctUntilChanged()
    }

    fun getArtifactDetailFlow(id: String): Flow<ArtifactDetail?> {
        return _uiState.map { it.artifactDetails[id] }.distinctUntilChanged()
    }

    fun getRecommendationReason(id: String): Flow<FeedRecommendationReason?> {
        return _uiState.map { it.recommendationReasons[id] }.distinctUntilChanged()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun dismissCrisis() {
        _uiState.update { it.copy(isCrisis = false) }
    }

    fun showSettingsComingSoon() {
        _uiState.update { it.copy(error = UiError(UiText.DynamicString("Resonance settings coming soon."))) }
    }

    fun playAudio(artifact: Artifact, reason: FeedRecommendationReason? = null) {
        adManager.recordInteraction(artifact.id)
        currentPlaybackReason = reason

        try {
            if (audioPlayer.currentArtifact.value?.id == artifact.id) {
                audioPlayer.togglePlayPause()
            } else {
                audioPlayer.playArtifact(artifact, source = PlaybackSource.FEED_PLAYBACK)
                viewModelScope.launch {
                    artifactEngagementRepository.recordPlay(
                        authRepository.currentUser.value?.uid,
                        artifact.id,
                        artifact.emotion
                    )
                }
            }
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FEED, "PLAYBACK_ERROR", throwable = e)
            _uiState.update { it.copy(error = UiError(UiText.StringResource(R.string.generic_error))) }
        }
    }

    fun reportArtifact(artifactId: String, reason: ReportReason, optionalDescription: String) {
        viewModelScope.launch {
            val deviceIdHash = uploadGuard.getDeviceFingerprint().hashCode()
            artifactRepository.submitReport(artifactId, reason, optionalDescription, deviceIdHash)
                .onFailure { e ->
                    _uiState.update { it.copy(error = ErrorMessageMapper.mapToUiError(e, onRetry = { reportArtifact(artifactId, reason, optionalDescription) })) }
                }
        }
    }

    fun submitFeedback(artifactId: String, type: FeedbackType) {
        val userId = authRepository.currentUser.value?.uid ?: run {
            _uiState.update { it.copy(error = UiError(UiText.StringResource(R.string.unauthenticated_presence))) }
            return
        }
        viewModelScope.launch {
            artifactEngagementRepository.submitPrivateFeedback(artifactId, userId, type).onSuccess {
                if (type == FeedbackType.SAFETY_CONCERN) {
                    _uiState.update { it.copy(error = UiError(UiText.DynamicString("Thanks for your concern. We'll look into this immediately."))) }
                } else {
                    _uiState.update { it.copy(error = UiError(UiText.DynamicString("Feedback received. This helps improve your feed."))) }
                    if (type == FeedbackType.NOT_FOR_ME) {
                        loadRankedFeed()
                    }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(error = ErrorMessageMapper.mapToUiError(e, onRetry = { submitFeedback(artifactId, type) })) }
            }
        }
    }

    fun loadArtifactDetails(artifactId: String) {
        if (detailsCache[artifactId] != null) {
            return
        }
        
        viewModelScope.launch {
            artifactRepository.getArtifactDetail(artifactId)
                .onSuccess { detail ->
                    detailsCache.put(artifactId, detail)
                    _uiState.update { it.copy(artifactDetails = it.artifactDetails + (artifactId to detail)) }
                }
                .onFailure { e ->
                    diagnosticLogger.error(DiagnosticCategory.FEED, "LOAD_DETAILS_FAILED", mapOf("artifactId" to artifactId), throwable = e)
                }
        }
    }

    fun hydrateArtifact(artifactId: String) {
        _uiState.update { it.copy(hydrationLevels = it.hydrationLevels + (artifactId to HydrationLevel.METADATA)) }
    }

    /**
     * Pre-caches the audio for a specific artifact to reduce playback latency.
     */
    fun preCacheArtifact(artifactId: String) {
        val artifact = _uiState.value.artifactCache[artifactId] ?: return
        if (artifact.audioUrl.isNotEmpty()) {
            audioPlayer.preCache(artifact)
        }
    }

    fun hydrateFromPaging(artifact: Artifact) {
        _uiState.update { current ->
            current.copy(
                artifactCache = current.artifactCache + (artifact.id to artifact),
                hydrationLevels = current.hydrationLevels + (artifact.id to (current.hydrationLevels[artifact.id] ?: HydrationLevel.SHELL))
            )
        }
    }

    /**
     * Records an impression for an artifact in the feed.
     * Part of Phase 10.2 Discovery Health Telemetry.
     */
    fun recordImpression(artifactId: String, reason: FeedRecommendationReason) {
        if (reason == FeedRecommendationReason.EMOTIONAL_RESONANCE || reason == FeedRecommendationReason.DISCOVERY) {
            diagnosticLogger.info(
                DiagnosticCategory.FEED, 
                "DISCOVERY_IMPRESSION", 
                mapOf("artifactId" to artifactId, "reason" to reason.name)
            )
        }
    }

    fun dismissPublishSession() {
        publishStateManager.dismissSession()
    }

    fun retryPublish(draftId: String) {
        publishStateManager.retryPublish(draftId)
    }

    fun cancelPublish() {
        // Redesign: For now, Cancel just dismisses the bar, or we could trigger deletion
        publishStateManager.dismissSession()
    }

    fun onArtifactFocused(artifactId: String) {
        loadArtifactDetails(artifactId)
    }

    override fun trimMemory(level: Int) {
        diagnosticLogger.debug(DiagnosticCategory.PERFORMANCE, "TRIM_MEMORY", mapOf("level" to level))
        // Use numeric values for clarity as constants are deprecated in some contexts
        // TRIM_MEMORY_COMPLETE = 80, TRIM_MEMORY_RUNNING_CRITICAL = 15
        if (level >= 80 || level == 15) {
            detailsCache.evictAll()
            _uiState.update { it.copy(artifactDetails = emptyMap()) }
            diagnosticLogger.info(DiagnosticCategory.PERFORMANCE, "MEMORY_CACHE_CLEARED_CRITICAL")
        } 
        // TRIM_MEMORY_UI_HIDDEN = 20, TRIM_MEMORY_RUNNING_LOW = 10
        else if (level >= 20 || level == 10) {
            detailsCache.trimToSize(2)
            _uiState.update { current ->
                // Keep only details currently in cache
                val keys = detailsCache.snapshot().keys
                current.copy(artifactDetails = current.artifactDetails.filterKeys { it in keys })
            }
            diagnosticLogger.info(DiagnosticCategory.PERFORMANCE, "MEMORY_CACHE_REDUCED")
        }
    }

    override fun onCleared() {
        super.onCleared()
        newContentListener?.remove()
        startupJob.cancel()
        memoryManager.unregister(this)
    }
}
