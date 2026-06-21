package com.saurabh.artifact.ui.feed

import android.util.Log
import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PublishStateManager
import com.saurabh.artifact.audio.ReviewSessionManager
import com.saurabh.artifact.domain.feed.GetFeedFlowUseCase
import com.saurabh.artifact.domain.feed.GetPersonalizedFeedFlowUseCase
import com.saurabh.artifact.domain.prompt.GetReflectionPromptUseCase
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.NotificationRepository
import com.saurabh.artifact.repository.CommentUnlockRepository
import com.saurabh.artifact.repository.SavedArtifactManager
import com.saurabh.artifact.service.AdManager
import com.saurabh.artifact.service.FeedComposer
import com.saurabh.artifact.service.PersonalizationEngine
import com.saurabh.artifact.service.SafetyLevel
import com.saurabh.artifact.security.UploadGuard
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.util.MemoryManager
import com.saurabh.artifact.util.MemoryTrimable
import com.saurabh.artifact.util.StartupTracer
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.UiError
import com.saurabh.artifact.ui.util.ErrorMessageMapper
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
    FULL        // Interactive waveform, atmospheric effects, comments
}

data class FeedUiState(
    val artifactCache: Map<String, Artifact> = emptyMap(),
    val hydrationLevels: Map<String, HydrationLevel> = emptyMap(),
    val artifactDetails: Map<String, ArtifactDetail> = emptyMap(),
    val recommendationReasons: Map<String, FeedRecommendationReason> = emptyMap(),
    val isRankedLoading: Boolean = false,
    val selectedEmotion: String? = null,
    val reflectionPrompt: ReflectionPrompt? = null,
    val isPromptLoading: Boolean = false,
    val safetyLevel: SafetyLevel = SafetyLevel.LOW,
    val isCrisis: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasNewContent: Boolean = false,
    val error: UiError? = null
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val authRepository: AuthRepository,
    private val notificationRepository: NotificationRepository,
    private val personalizationEngine: PersonalizationEngine,
    private val adManager: AdManager,
    private val memoryManager: MemoryManager,
    startupCoordinator: StartupCoordinator,
    savedArtifactManager: SavedArtifactManager,
    private val firestore: FirebaseFirestore,
    val audioPlayer: PlaybackCoordinator,
    private val cleanupManager: ArtifactCleanupManager,
    private val reviewSessionManager: ReviewSessionManager,
    private val publishStateManager: PublishStateManager,
    private val commentUnlockRepository: CommentUnlockRepository,
    private val uploadGuard: UploadGuard,
    private val feedComposer: FeedComposer,
    getFeedFlowUseCase: GetFeedFlowUseCase,
    getPersonalizedFeedFlowUseCase: GetPersonalizedFeedFlowUseCase,
    private val getReflectionPromptUseCase: GetReflectionPromptUseCase
) : ViewModel(), MemoryTrimable {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

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
                notificationRepository.listenNotifications(user.uid)
                    .map { items -> items.count { !it.isRead } }
            } else {
                flowOf(0)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val currentPublishState: StateFlow<PublishState?> = publishStateManager.currentPublishState

    private val _refreshTrigger = MutableStateFlow(0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val artifacts: Flow<PagingData<Artifact>> = combine(
        _uiState.map { it.selectedEmotion }.distinctUntilChanged(),
        _refreshTrigger
    ) { emotion, _ -> emotion }.flatMapLatest { emotion ->
        getFeedFlowUseCase(emotion)
    }.map { pagingData ->
        pagingData.map { artifact ->
            hydrateFromPaging(artifact)
            artifact
        }
    }.cachedIn(viewModelScope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val personalizedArtifacts: Flow<PagingData<Artifact>> = combine(
        _uiState.map { it.selectedEmotion }.distinctUntilChanged(),
        _refreshTrigger
    ) { emotion, _ -> emotion }.flatMapLatest { emotion ->
        getPersonalizedFeedFlowUseCase(emotion)
    }.map { pagingData ->
        pagingData.map { artifact ->
            hydrateFromPaging(artifact)
            artifact
        }
    }.cachedIn(viewModelScope)

    // Legacy compatibility accessors
    val isRankedLoading = _uiState.map { it.isRankedLoading }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val selectedEmotion = _uiState.map { it.selectedEmotion }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val reflectionPrompt = _uiState.map { it.reflectionPrompt }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isPromptLoading = _uiState.map { it.isPromptLoading }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val safetyLevel = _uiState.map { it.safetyLevel }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SafetyLevel.LOW)
    val isCrisis = _uiState.map { it.isCrisis }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isRefreshing = _uiState.map { it.isRefreshing }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val hasNewContent = _uiState.map { it.hasNewContent }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val error = _uiState.map { it.error }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
    }

    private val startupJob = kotlinx.coroutines.SupervisorJob()
    private var started = false

    fun start() {
        if (currentUserId == null) {
            Log.w("AuthGuard", "FeedViewModel.start() blocked: User is null.")
            _uiState.update { it.copy(artifactCache = emptyMap(), hydrationLevels = emptyMap()) }
            return
        }
        if (started) return
        started = true

        Log.d("APP_FLOW", "FeedViewModel.start() - Production Staggered Hydration")
        StartupTracer.mark("Feed Hydration Started")
        StartupMetrics.onFeedHydrationStart()
        
        viewModelScope.launch {
            // PHASE 1: Critical UI Path (Primary Feed Content)
            launch(Dispatchers.Default) {
                runCatching { 
                    loadRankedFeed()
                    StartupTracer.mark("Ranked Feed Loaded")
                }.onFailure { Log.e("FeedHydration", "Ranked feed load failed", it) }
            }

            // PHASE 2: Contextual Enrichment (Prompts & Personalization Init)
            launch {
                delay(1500.milliseconds) 
                runCatching { 
                    refreshReflectionPrompt()
                    StartupTracer.mark("Reflection Prompt Hydrated")
                }.onFailure { Log.e("FeedHydration", "Prompt refresh failed", it) }
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
                    Log.e("FeedViewModel", "New content listener error", error)
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
                if (progress != null && progress.isValidationMet) {
                    val artifactId = progress.artifactId
                    commentUnlockRepository.unlockArtifact(artifactId)
                    Log.d("FeedViewModel", "Artifact $artifactId unlocked via listener authority")
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
            if (_uiState.value.isPromptLoading) return@launch
            
            _uiState.update { it.copy(isPromptLoading = true) }
            runCatching {
                val result = getReflectionPromptUseCase(
                    emotion = _uiState.value.selectedEmotion,
                    context = context
                )
                _uiState.update { it.copy(
                    safetyLevel = result.safetyLevel,
                    isCrisis = result.isCrisis,
                    reflectionPrompt = result.prompt
                ) }
            }.onFailure {
                Log.e("FeedViewModel", "Error refreshing prompt", it)
            }
            _uiState.update { it.copy(isPromptLoading = false) }
        }
    }

    fun setEmotionFilter(emotion: String?) {
        _uiState.update { it.copy(selectedEmotion = emotion) }
        loadRankedFeed()
    }

    private val _unfinishedArtifacts = MutableStateFlow<List<FeedArtifact>>(emptyList())
    val unfinishedArtifacts: StateFlow<List<FeedArtifact>> = _unfinishedArtifacts.asStateFlow()

    fun loadRankedFeed(): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            val userId = currentUserId ?: run {
                Log.w("AuthGuard", "loadRankedFeed blocked: User is null.")
                return@launch
            }
            _uiState.update { it.copy(isRankedLoading = true) }
            
            runCatching {
                val feedItems = withContext(Dispatchers.Default) {
                    feedComposer.composeFeed(userId)
                }

                // Cache recommendation reasons for the UI
                val reasons = feedItems.associateBy({ it.artifact.id }, { it.reason })
                _uiState.update { it.copy(recommendationReasons = it.recommendationReasons + reasons) }

                // Currently, we only expose the unfinished items to the UI for the top section
                val unfinishedItems = feedItems.filter { it.isUnfinished }
                _unfinishedArtifacts.value = unfinishedItems
                
                unfinishedItems.take(3).forEach { feedArtifact ->
                    audioPlayer.preCache(feedArtifact.artifact)
                }
            }.onFailure {
                Log.e("FeedViewModel", "Error loading ranked feed", it)
            }
            _uiState.update { it.copy(isRankedLoading = false) }
        }
    }

    fun refreshFeed() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, hasNewContent = false) }
            lastLoadedTimestamp = System.currentTimeMillis()
            
            val rankedJob = loadRankedFeed()
            val promptJob = refreshReflectionPrompt()
            
            _refreshTrigger.value += 1
            
            rankedJob.join()
            promptJob.join()
            
            delay(500.milliseconds)
            
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun updateHydrationLevels(updates: Map<String, HydrationLevel>) {
        _uiState.update { it.copy(hydrationLevels = it.hydrationLevels + updates) }
    }

    fun getArtifactFlow(id: String): Flow<Artifact?> {
        return _uiState.map { it.artifactCache[id] }.distinctUntilChanged()
    }

    fun getRecommendationReason(id: String): Flow<FeedRecommendationReason?> {
        return _uiState.map { it.recommendationReasons[id] }.distinctUntilChanged()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun deleteArtifact(artifactId: String) {
        viewModelScope.launch {
            cleanupManager.deleteArtifact(artifactId)
                .onSuccess {
                    _uiState.update { it.copy(error = UiError(UiText.StringResource(R.string.reflection_deleted))) }
                    _refreshTrigger.value += 1
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = ErrorMessageMapper.mapToUiError(e, onRetry = { deleteArtifact(artifactId) })) }
                }
        }
    }

    fun dismissCrisis() {
        _uiState.update { it.copy(isCrisis = false) }
    }

    fun showSettingsComingSoon() {
        _uiState.update { it.copy(error = UiError(UiText.DynamicString("Resonance settings coming soon."))) }
    }

    fun playAudio(artifact: Artifact) {
        adManager.recordInteraction(artifact.id)

        try {
            if (audioPlayer.currentArtifact.value?.id == artifact.id) {
                audioPlayer.togglePlayPause()
            } else {
                audioPlayer.playArtifact(artifact)
                viewModelScope.launch {
                    artifactRepository.recordPlay(
                        authRepository.currentUser.value?.uid,
                        artifact.emotion
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("FeedViewModel", "Error playing audio", e)
            _uiState.update { it.copy(error = UiError(UiText.StringResource(R.string.generic_error))) }
        }
    }

    fun reportArtifact(artifactId: String, reason: ReportReason, details: String) {
        viewModelScope.launch {
            val deviceId = uploadGuard.getDeviceFingerprint().hashCode()
            artifactRepository.submitReport(artifactId, reason, details, deviceId)
                .onSuccess {
                    _uiState.update { it.copy(error = UiError(UiText.DynamicString("Report submitted anonymously. Thank you for keeping Artifact safe."))) }
                    _refreshTrigger.value += 1
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = ErrorMessageMapper.mapToUiError(e, onRetry = { reportArtifact(artifactId, reason, details) })) }
                }
        }
    }

    fun submitFeedback(artifactId: String, type: FeedbackType) {
        val userId = authRepository.currentUser.value?.uid ?: run {
            _uiState.update { it.copy(error = UiError(UiText.StringResource(R.string.unauthenticated_presence))) }
            return
        }
        viewModelScope.launch {
            artifactRepository.submitPrivateFeedback(artifactId, userId, type).onSuccess {
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
                    Log.e("FeedViewModel", "Error loading details for $artifactId", e)
                }
        }
    }

    fun hydrateArtifact(artifactId: String) {
        _uiState.update { it.copy(hydrationLevels = it.hydrationLevels + (artifactId to HydrationLevel.METADATA)) }
    }

    fun hydrateFromPaging(artifact: Artifact) {
        _uiState.update { current ->
            current.copy(
                artifactCache = current.artifactCache + (artifact.id to artifact),
                hydrationLevels = current.hydrationLevels + (artifact.id to (current.hydrationLevels[artifact.id] ?: HydrationLevel.SHELL))
            )
        }
    }

    fun dismissPublishSession() {
        publishStateManager.dismissSession()
    }

    fun retryPublish(draftId: String) {
        publishStateManager.retryPublish(draftId)
    }

    fun cancelPublish(draftId: String) {
        // Redesign: For now, Cancel just dismisses the bar, or we could trigger deletion
        publishStateManager.dismissSession()
    }

    fun onArtifactFocused(artifactId: String) {
        loadArtifactDetails(artifactId)
    }

    override fun trimMemory(level: Int) {
        Log.d("FeedViewModel", "Trimming memory, level: $level")
        // Use numeric values for clarity as constants are deprecated in some contexts
        // TRIM_MEMORY_COMPLETE = 80, TRIM_MEMORY_RUNNING_CRITICAL = 15
        if (level >= 80 || level == 15) {
            detailsCache.evictAll()
            _uiState.update { it.copy(artifactDetails = emptyMap()) }
            Log.d("FeedViewModel", "Cache cleared due to high memory pressure")
        } 
        // TRIM_MEMORY_UI_HIDDEN = 20, TRIM_MEMORY_RUNNING_LOW = 10
        else if (level >= 20 || level == 10) {
            detailsCache.trimToSize(2)
            _uiState.update { current ->
                // Keep only details currently in cache
                val keys = detailsCache.snapshot().keys
                current.copy(artifactDetails = current.artifactDetails.filterKeys { it in keys })
            }
            Log.d("FeedViewModel", "Cache reduced to 2 items")
        }
    }

    override fun onCleared() {
        super.onCleared()
        newContentListener?.remove()
        startupJob.cancel()
        memoryManager.unregister(this)
    }
}
