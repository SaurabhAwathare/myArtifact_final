package com.saurabh.artifact.ui.feed

import android.content.ComponentCallbacks2
import android.util.Log
import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackType
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.ReactionRepository
import com.saurabh.artifact.service.AdManager
import com.saurabh.artifact.service.FeedRanker
import com.saurabh.artifact.service.PersonalizationEngine
import com.saurabh.artifact.service.SafetyEvaluator
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.service.SafetyLevel
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.data.local.RecordingStatus
import com.saurabh.artifact.util.MemoryManager
import com.saurabh.artifact.util.MemoryTrimable
import com.saurabh.artifact.util.StartupTracer
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.R
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.yield
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import javax.inject.Inject

enum class HydrationLevel {
    SHELL,      // Static frame, default fonts, no animations
    METADATA,   // Adds reactions, counts, and tags
    ENRICHED,   // Adds play button, static waveform
    FULL        // Interactive waveform, atmospheric effects, comments
}

data class FeedUiState(
    val rankedArtifactIds: List<String> = emptyList(),
    val artifactCache: Map<String, Artifact> = emptyMap(),
    val hydrationLevels: Map<String, HydrationLevel> = emptyMap(),
    val artifactDetails: Map<String, ArtifactDetail> = emptyMap(),
    val isRankedLoading: Boolean = false,
    val selectedEmotion: String? = null,
    val reflectionPrompt: ReflectionPrompt? = null,
    val isPromptLoading: Boolean = false,
    val safetyLevel: SafetyLevel = SafetyLevel.LOW,
    val isCrisis: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasNewContent: Boolean = false,
    val error: UiText? = null
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val authRepository: AuthRepository,
    private val reactionRepository: ReactionRepository,
    private val personalizationEngine: PersonalizationEngine,
    private val feedRanker: FeedRanker,
    private val safetyEvaluator: SafetyEvaluator,
    private val adManager: AdManager,
    private val feedComposer: com.saurabh.artifact.service.FeedComposer,
    private val feedRepository: com.saurabh.artifact.repository.FeedRepository,
    private val memoryManager: MemoryManager,
    private val startupCoordinator: StartupCoordinator,
    private val savedArtifactManager: com.saurabh.artifact.repository.SavedArtifactManager,
    private val firestore: FirebaseFirestore,
    val audioPlayer: PlaybackCoordinator,
    private val reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager,
    private val commentUnlockRepository: com.saurabh.artifact.repository.CommentUnlockRepository,
    private val uploadGuard: com.saurabh.artifact.security.UploadGuard
) : ViewModel(), MemoryTrimable {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    val unlockedArtifactIds: StateFlow<Set<String>> = commentUnlockRepository.unlockedArtifactIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val savedIds = savedArtifactManager.savedIds

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
    val currentPosition = audioPlayer.currentPosition
    val durationMs = audioPlayer.durationMs
    val startupStage = startupCoordinator.stage
    val currentUserId: String? get() = authRepository.currentUser.value?.uid

    private val _refreshTrigger = MutableStateFlow(0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val artifacts: Flow<PagingData<Artifact>> = combine(
        _uiState.map { it.selectedEmotion }.distinctUntilChanged(),
        _refreshTrigger
    ) { emotion, _ -> emotion }.flatMapLatest { emotion ->
        artifactRepository.getArtifactsPager(emotion)
    }.cachedIn(viewModelScope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val personalizedArtifacts: Flow<PagingData<Artifact>> = _refreshTrigger.flatMapLatest { _ ->
        val userId = authRepository.currentUser.value?.uid ?: return@flatMapLatest flowOf(PagingData.empty<Artifact>())
        Pager(
            config = PagingConfig(
                pageSize = 10,
                prefetchDistance = 2,
                initialLoadSize = 10,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { 
                com.saurabh.artifact.data.paging.PersonalizedPagingSource(
                    userId = userId,
                    feedRepository = feedRepository,
                    feedRanker = feedRanker
                ) 
            }
        ).flow
    }.cachedIn(viewModelScope)

    // Legacy compatibility accessors
    val rankedArtifactIds = _uiState.map { it.rankedArtifactIds }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val isRankedLoading = _uiState.map { it.isRankedLoading }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val selectedEmotion = _uiState.map { it.selectedEmotion }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val reflectionPrompt = _uiState.map { it.reflectionPrompt }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val isPromptLoading = _uiState.map { it.isPromptLoading }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val safetyLevel = _uiState.map { it.safetyLevel }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, SafetyLevel.LOW)
    val isCrisis = _uiState.map { it.isCrisis }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val isRefreshing = _uiState.map { it.isRefreshing }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val hasNewContent = _uiState.map { it.hasNewContent }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val error = _uiState.map { it.error }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val artifactDetails = _uiState.map { it.artifactDetails }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        memoryManager.register(this)
        start()

        // Listen for save messages
        viewModelScope.launch {
            savedArtifactManager.messages.collect { message ->
                _uiState.update { it.copy(error = ErrorMessageMapper.map(message)) }
            }
        }
    }

    private val startupJob = kotlinx.coroutines.SupervisorJob()
    private val startupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + startupJob)
    private var started = false

    fun start() {
        if (started) return
        started = true

        Log.d("APP_FLOW", "FeedViewModel.start() - Production Staggered Hydration")
        StartupTracer.mark("Feed Hydration Started")
        StartupMetrics.onFeedHydrationStart()
        
        viewModelScope.launch {
            // PHASE 1: Critical UI Path (Primary Feed Content)
            // Run on Default dispatcher to keep Main thread free for first frame
            // REMOVED blocking .join() to allow concurrent hydration of shell
            val feedJob = launch(Dispatchers.Default) {
                runCatching { 
                    loadRankedFeed()
                    StartupTracer.mark("Ranked Feed Loaded")
                }.onFailure { Log.e("FeedHydration", "Ranked feed load failed", it) }
            }

            // PHASE 2: Contextual Enrichment (Prompts & Personalization Init)
            // Deferred intentionally to prioritize first artifact render and reduce system pressure
            launch {
                delay(1500) // Yield significantly to UI and Phase 1
                runCatching { 
                    refreshReflectionPrompt()
                    StartupTracer.mark("Reflection Prompt Hydrated")
                }.onFailure { Log.e("FeedHydration", "Prompt refresh failed", it) }
            }

            // PHASE 3: Background & Deferred (Low priority syncs)
            launch {
                delay(4000) // Well after UI is stable and user is likely interacting
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

        // Listen for ANY new public artifact. 
        // We could filter by emotion, but usually "New Reflections" is a global signal.
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
            reviewSessionManager.reviewProgress.collect { session ->
                if (session.isThresholdMet) {
                    val artifactId = session.artifactId
                    if (artifactId != null) {
                        commentUnlockRepository.unlockArtifact(artifactId)
                        Log.d("FeedViewModel", "Artifact $artifactId unlocked via robust tracker")
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
            if (_uiState.value.isPromptLoading) return@launch
            
            _uiState.update { it.copy(isPromptLoading = true) }
            runCatching {
                val assessment = withContext(Dispatchers.Default) {
                    safetyEvaluator.evaluate(context)
                }
                _uiState.update { it.copy(safetyLevel = assessment.level, isCrisis = assessment.isCrisis) }
                adManager.updateSafetyContext(assessment)

                val prompt = artifactRepository.getSmartReflectionPrompt(
                    emotion = _uiState.value.selectedEmotion,
                    context = context,
                    timeOfDay = getTimeOfDayContext()
                )
                _uiState.update { it.copy(reflectionPrompt = prompt) }
            }.onFailure {
                Log.e("FeedViewModel", "Error refreshing prompt", it)
            }
            _uiState.update { it.copy(isPromptLoading = false) }
        }
    }

    private fun getTimeOfDayContext(): String {
        return when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }
    }

    fun setEmotionFilter(emotion: String?) {
        _uiState.update { it.copy(selectedEmotion = emotion) }
        loadRankedFeed()
    }

    private fun preCacheTopArtifacts(artifacts: List<Artifact>) {
        artifacts.take(3).forEach { artifact ->
            audioPlayer.preCache(artifact)
        }
    }

    private val _unfinishedArtifacts = MutableStateFlow<List<FeedArtifact>>(emptyList())
    val unfinishedArtifacts: StateFlow<List<FeedArtifact>> = _unfinishedArtifacts.asStateFlow()

    fun loadRankedFeed(): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            val userId = authRepository.currentUser.value?.uid ?: return@launch
            _uiState.update { it.copy(isRankedLoading = true) }
            
            runCatching {
                // Fetch unfinished sessions (small list, usually few)
                val unfinishedSessions = withContext(Dispatchers.Default) {
                    feedRepository.getUnfinishedSessions(userId)
                }
                
                // Fetch discovery artifacts to hydrate unfinished sessions if needed
                // For simplicity, we just fetch a small batch here. 
                // In a fuller implementation, FeedComposer would handle this better.
                val discovery = artifactRepository.getCandidateArtifacts(userId = userId, limit = 20)

                val unfinishedItems = unfinishedSessions.mapNotNull { session ->
                    val artifact = discovery.find { it.id == session.artifactId } ?: return@mapNotNull null
                    FeedArtifact(
                        artifact = artifact,
                        reason = FeedRecommendationReason.CONTINUE_LISTENING,
                        isUnfinished = true,
                        lastPositionMs = session.lastPositionMs
                    )
                }

                _unfinishedArtifacts.value = unfinishedItems
                
                // Pre-cache top items for immediate playback
                unfinishedItems.take(3).forEach { feedArtifact ->
                    audioPlayer.preCache(feedArtifact.artifact)
                }
                
                // Note: The main ranked feed is now handled by Paging 3 (personalizedArtifacts)
                // We just trigger a refresh if needed via _refreshTrigger.
            }.onFailure {
                Log.e("FeedViewModel", "Error loading unfinished items", it)
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
            
            // Trigger Recent feed refresh
            _refreshTrigger.value += 1
            
            // Wait for both ranked feed and prompt to finish
            rankedJob.join()
            promptJob.join()
            
            // Optional: small delay to make the refresh feel "calm" and "soft"
            delay(500)
            
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun updateHydrationLevels(updates: Map<String, HydrationLevel>) {
        _uiState.update { it.copy(hydrationLevels = it.hydrationLevels + updates) }
    }

    fun getArtifactFlow(id: String): Flow<Artifact?> {
        return _uiState.map { it.artifactCache[id] }.distinctUntilChanged()
    }

    fun getHydrationLevelFlow(id: String): Flow<HydrationLevel> {
        return _uiState.map { it.hydrationLevels[id] ?: HydrationLevel.SHELL }.distinctUntilChanged()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun deleteArtifact(artifactId: String) {
        viewModelScope.launch {
            artifactRepository.deletePublishedArtifact(artifactId)
                .onSuccess {
                    _uiState.update { it.copy(error = UiText.StringResource(R.string.reflection_deleted)) }
                    // Trigger a refresh to reflect the local Room update in the PagingData
                    _refreshTrigger.value += 1
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = ErrorMessageMapper.map(e)) }
                }
        }
    }

    fun dismissCrisis() {
        _uiState.update { it.copy(isCrisis = false) }
    }

    fun showSettingsComingSoon() {
        _uiState.update { it.copy(error = UiText.DynamicString("Resonance settings coming soon.")) }
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
            android.util.Log.e("FeedViewModel", "Error playing audio", e)
            _uiState.update { it.copy(error = UiText.StringResource(R.string.generic_error)) }
        }
    }

    fun toggleSave(artifact: Artifact) {
        savedArtifactManager.toggleSave(artifact)
    }

    fun reportArtifact(artifactId: String, reason: com.saurabh.artifact.model.ReportReason, details: String) {
        viewModelScope.launch {
            val deviceId = uploadGuard.getDeviceFingerprint().hashCode()
            artifactRepository.submitReport(artifactId, reason, details, deviceId)
                .onSuccess {
                    _uiState.update { it.copy(error = UiText.DynamicString("Report submitted anonymously. Thank you for keeping Artifact safe.")) }
                    // Trigger a refresh to reflect the local Room update in the PagingData
                    _refreshTrigger.value += 1
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = ErrorMessageMapper.map(e)) }
                }
        }
    }

    fun reactToArtifact(artifactId: String, type: ReactionType) {
        val userId = authRepository.currentUser.value?.uid ?: run {
            _uiState.update { it.copy(error = UiText.StringResource(R.string.unauthenticated_presence)) }
            return
        }
        
        // Optimistic UI for immediate feedback
        val currentArtifact = _uiState.value.artifactCache[artifactId]
        if (currentArtifact != null) {
            val updatedArtifact = currentArtifact.copy(
                reactionCount = currentArtifact.reactionCount + 1
                // Note: We don't have a local 'userReaction' flag in Artifact model, 
                // but incrementing the count provides immediate feedback.
            )
            _uiState.update { current ->
                current.copy(artifactCache = current.artifactCache + (artifactId to updatedArtifact))
            }
        }

        viewModelScope.launch {
            reactionRepository.toggleReaction(artifactId, userId, type).onSuccess {
                // Refresh local cache for final consistency
                val updatedArtifact = artifactRepository.getArtifactById(artifactId)
                if (updatedArtifact != null) {
                    _uiState.update { current ->
                        current.copy(artifactCache = current.artifactCache + (artifactId to updatedArtifact.slimForFeed()))
                    }
                }
            }.onFailure {
                // Rollback optimistic update on failure
                if (currentArtifact != null) {
                    _uiState.update { current ->
                        current.copy(artifactCache = current.artifactCache + (artifactId to currentArtifact))
                    }
                }
                _uiState.update { it.copy(error = UiText.StringResource(R.string.generic_error)) }
            }
        }
    }

    fun submitFeedback(artifactId: String, type: FeedbackType) {
        val userId = authRepository.currentUser.value?.uid ?: run {
            _uiState.update { it.copy(error = UiText.StringResource(R.string.unauthenticated_presence)) }
            return
        }
        viewModelScope.launch {
            artifactRepository.submitPrivateFeedback(artifactId, userId, type).onSuccess {
                if (type == FeedbackType.SAFETY_CONCERN) {
                    _uiState.update { it.copy(error = UiText.DynamicString("Thanks for your concern. We'll look into this immediately.")) }
                } else {
                    _uiState.update { it.copy(error = UiText.DynamicString("Feedback received. This helps improve your feed.")) }
                    if (type == FeedbackType.NOT_FOR_ME) {
                        loadRankedFeed()
                    }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(error = ErrorMessageMapper.map(e)) }
            }
        }
    }

    fun updateArtifactVisibility(artifactId: String, mode: com.saurabh.artifact.model.ReactionVisibilityMode) {
        viewModelScope.launch {
            reactionRepository.setVisibilityMode(artifactId, mode).onFailure { e ->
                _uiState.update { it.copy(error = ErrorMessageMapper.map(e)) }
            }
        }
    }
    fun sendReply(artifactId: String, message: String) {
        if (message.isBlank()) return
        viewModelScope.launch {
            artifactRepository.sendReply(artifactId, message).onFailure { e ->
                _uiState.update { it.copy(error = ErrorMessageMapper.map(e)) }
            }
        }
    }

    fun loadArtifactDetails(artifactId: String) {
        if (detailsCache.get(artifactId) != null) {
            return
        }
        
        viewModelScope.launch {
            try {
                val detail = artifactRepository.getArtifactDetail(artifactId)
                detailsCache.put(artifactId, detail)
                _uiState.update { it.copy(artifactDetails = it.artifactDetails + (artifactId to detail)) }
            } catch (e: Exception) {
                android.util.Log.e("FeedViewModel", "Error loading details", e)
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

    fun onArtifactFocused(artifactId: String) {
        loadArtifactDetails(artifactId)
    }

    override fun trimMemory(level: Int) {
        Log.d("FeedViewModel", "Trimming memory, level: $level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            detailsCache.evictAll()
            _uiState.update { it.copy(artifactDetails = emptyMap()) }
            Log.d("FeedViewModel", "Cache cleared due to high memory pressure")
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            detailsCache.trimToSize(2)
            Log.d("FeedViewModel", "Cache reduced to 2 items")
        }
    }

    override fun onCleared() {
        super.onCleared()
        newContentListener?.remove()
        startupJob.cancel()
        memoryManager.unregister(this)
        // Playback ownership is now handled by the Coordinator.
    }
}
