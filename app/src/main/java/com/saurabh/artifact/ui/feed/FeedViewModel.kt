package com.saurabh.artifact.ui.feed

import android.content.ComponentCallbacks2
import android.util.Log
import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
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
    val error: String? = null
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
    val audioPlayer: PlaybackCoordinator,
    private val reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager,
    private val commentUnlockRepository: com.saurabh.artifact.repository.CommentUnlockRepository
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

    // Legacy compatibility accessors
    val rankedArtifactIds = _uiState.map { it.rankedArtifactIds }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val isRankedLoading = _uiState.map { it.isRankedLoading }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val selectedEmotion = _uiState.map { it.selectedEmotion }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val reflectionPrompt = _uiState.map { it.reflectionPrompt }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val isPromptLoading = _uiState.map { it.isPromptLoading }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val safetyLevel = _uiState.map { it.safetyLevel }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, SafetyLevel.LOW)
    val isCrisis = _uiState.map { it.isCrisis }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val isRefreshing = _uiState.map { it.isRefreshing }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val error = _uiState.map { it.error }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val artifactDetails = _uiState.map { it.artifactDetails }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        memoryManager.register(this)
        start()

        // Listen for save messages
        viewModelScope.launch {
            savedArtifactManager.messages.collect { message ->
                _uiState.update { it.copy(error = message) }
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
                StartupTracer.mark("Personalization Engine Initialized")
            }
            
            launch { observePlaybackCompletion() }
            launch { observePlaybackProgress() }
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
                // Offload heavy composition and fetching to Default dispatcher
                val fullFeed = withContext(Dispatchers.Default) {
                    feedComposer.composeFeed(userId)
                }
                
                // STAGGERED HYDRATION: Process in batches to reduce main-thread pressure
                val batch1Size = 5 // Reduced from 10 to minimize first frame delay
                
                val (unfinished, rankedBatch1, remainingRanked) = withContext(Dispatchers.Default) {
                    val unfinished = fullFeed.filter { it.isUnfinished }
                    val ranked = fullFeed.filter { !it.isUnfinished }
                    
                    // Optimization: Use slimForFeed during the mapping phase
                    val b1 = ranked.take(batch1Size).map { it.artifact.slimForFeed() }
                    val rem = ranked.drop(batch1Size).take(35) // Limit total to 40
                    Triple(unfinished, b1, rem)
                }

                // Stage 1: Immediate update with critical first batch
                _unfinishedArtifacts.value = unfinished
                
                val b1Ids = rankedBatch1.map { it.id }
                val b1Cache = rankedBatch1.associateBy { it.id }
                
                _uiState.update { current ->
                    current.copy(
                        rankedArtifactIds = b1Ids,
                        artifactCache = current.artifactCache + b1Cache,
                        hydrationLevels = current.hydrationLevels + b1Ids.filter { !current.hydrationLevels.containsKey(it) }.associateWith { HydrationLevel.SHELL }
                    )
                }
                
                // Pre-cache top items for "Instant Play"
                preCacheTopArtifacts(rankedBatch1)

                // Yield to UI to allow immediate rendering of the first 5 items
                yield() 
                delay(64) // roughly 4 frames at 60fps

                // Stage 2: Background processing for remaining items
                if (remainingRanked.isNotEmpty()) {
                    val processedRemaining = withContext(Dispatchers.Default) {
                        remainingRanked.map { it.artifact.slimForFeed() }
                    }
                    
                    val remIds = processedRemaining.map { it.id }
                    val remCache = processedRemaining.associateBy { it.id }
                    
                    _uiState.update { current ->
                        current.copy(
                            rankedArtifactIds = current.rankedArtifactIds + remIds,
                            artifactCache = current.artifactCache + remCache,
                            hydrationLevels = current.hydrationLevels + remIds.filter { !current.hydrationLevels.containsKey(it) }.associateWith { HydrationLevel.SHELL }
                        )
                    }
                }
            }.onFailure {
                Log.e("FeedViewModel", "Error loading composed feed", it)
            }
            _uiState.update { it.copy(isRankedLoading = false) }
        }
    }

    fun refreshFeed() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            
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
            _uiState.update { it.copy(error = "Unable to play this artifact.") }
        }
    }

    fun toggleSave(artifact: Artifact) {
        savedArtifactManager.toggleSave(artifact)
    }

    fun reportArtifact(artifactId: String, reason: com.saurabh.artifact.model.ReportReason, details: String) {
        viewModelScope.launch {
            val deviceId = authRepository.userData.value?.id?.hashCode() ?: 0
            artifactRepository.submitReport(artifactId, reason, details, deviceId)
                .onSuccess {
                    _uiState.update { it.copy(error = "Report submitted anonymously. Thank you for keeping Artifact safe.") }
                    loadRankedFeed()
                }
                .onFailure {
                    _uiState.update { it.copy(error = "Failed to submit report. Please try again.") }
                }
        }
    }

    fun reactToArtifact(artifactId: String, type: ReactionType) {
        val userId = authRepository.currentUser.value?.uid ?: run {
            _uiState.update { it.copy(error = "Sign in to interact.") }
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
                _uiState.update { it.copy(error = "We couldn't share your resonance right now. Please try again.") }
            }
        }
    }

    fun submitFeedback(artifactId: String, type: FeedbackType) {
        val userId = authRepository.currentUser.value?.uid ?: run {
            _uiState.update { it.copy(error = "Sign in to provide feedback.") }
            return
        }
        viewModelScope.launch {
            artifactRepository.submitPrivateFeedback(artifactId, userId, type).onSuccess {
                if (type == FeedbackType.SAFETY_CONCERN) {
                    _uiState.update { it.copy(error = "Thanks for your concern. We'll look into this immediately.") }
                } else {
                    _uiState.update { it.copy(error = "Feedback received. This helps improve your feed.") }
                    if (type == FeedbackType.NOT_FOR_ME) {
                        loadRankedFeed()
                    }
                }
            }.onFailure {
                _uiState.update { it.copy(error = "Couldn't submit feedback. Please try again.") }
            }
        }
    }

    fun updateArtifactVisibility(artifactId: String, mode: com.saurabh.artifact.model.ReactionVisibilityMode) {
        viewModelScope.launch {
            reactionRepository.setVisibilityMode(artifactId, mode).onFailure {
                _uiState.update { it.copy(error = "Failed to update visibility.") }
            }
        }
    }
    fun sendReply(artifactId: String, message: String) {
        if (message.isBlank()) return
        viewModelScope.launch {
            artifactRepository.sendReply(artifactId, message).onFailure {
                _uiState.update { it.copy(error = "Failed to send reply. Please try again.") }
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
        startupJob.cancel()
        memoryManager.unregister(this)
        // Playback ownership is now handled by the Coordinator.
    }
}
