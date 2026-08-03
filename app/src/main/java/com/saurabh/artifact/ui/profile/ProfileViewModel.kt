package com.saurabh.artifact.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.data.mapper.DraftToArtifactMapper
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.ui.profile.models.DraftUiModel
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.R
import com.saurabh.artifact.domain.profile.ProfileData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class ProfileTab(val title: String) {
    PUBLISHED("Published"),
    DRAFTS("Drafts"),
    SAVED("Stayed With Me")
}

data class ProfileUiState(
    val userProfile: User? = null,
    val sigilConfig: SigilConfig = SigilConfig(),
    val isSelf: Boolean = false,
    val isResonating: Boolean = false,
    val selectedTab: ProfileTab = ProfileTab.PUBLISHED,
    val publishedArtifacts: List<Artifact> = emptyList(),
    val cloudDrafts: List<Artifact> = emptyList(),
    val savedArtifacts: List<Artifact> = emptyList(),
    val localDrafts: List<DraftUiModel> = emptyList(),
    val logoutState: LogoutState = LogoutState.Idle,
    val message: UiText? = null,
    val isLoading: Boolean = true,
    val isActionLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val currentlyPlayingArtifact: Artifact? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0,
    val durationMs: Long = 0,
)

private data class ProfileContent(
    val data: ProfileData?,
    val sigilConfig: SigilConfig,
    val selectedTab: ProfileTab,
    val logoutState: LogoutState,
    val message: UiText?,
    val isActionLoading: Boolean,
    val isRefreshing: Boolean,
    val mappedLocalDrafts: List<DraftUiModel>,
    val mappedPublishedArtifacts: List<Artifact>,
    val mappedCloudDrafts: List<Artifact>,
    val mappedSavedArtifacts: List<Artifact>,
)

private data class PlaybackState(
    val currentlyPlaying: Artifact?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val position: Duration,
    val duration: Duration
)

@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    userProfileManager: UserProfileManager,
    private val savedArtifactManager: SavedArtifactManager,
    private val playbackCoordinator: PlaybackCoordinator,
    getProfileDataUseCase: com.saurabh.artifact.domain.profile.GetProfileDataUseCase,
    private val profileInteractionUseCase: com.saurabh.artifact.domain.profile.ProfileInteractionUseCase,
    private val logoutCoordinator: com.saurabh.artifact.domain.auth.LogoutCoordinator,
    private val diagnosticLogger: DiagnosticLogger,
    private val draftMapper: DraftToArtifactMapper
) : ViewModel() {

    val currentUserId: String? get() = authRepository.currentUser.value?.uid
    val savedIds = savedArtifactManager.savedIds

    private val _targetUserId = MutableStateFlow<String?>(null)
    private val _selectedTab = MutableStateFlow(ProfileTab.PUBLISHED)
    private val _logoutState = MutableStateFlow<LogoutState>(LogoutState.Idle)
    private val _message = MutableStateFlow<UiText?>(null)
    private val _isActionLoading = MutableStateFlow(value = false)
    private val _isRefreshing = MutableStateFlow(value = false)
    private val _refreshTrigger = MutableStateFlow(0)

    // Phase 4: Temporary memory buffers for input fields (Invariant 8)
    private val _editingId = MutableStateFlow<String?>(null)
    private val _titleInput = MutableStateFlow<String?>(null)
    private var renameDebounceJob: kotlinx.coroutines.Job? = null

    private val profileDataFlow = _refreshTrigger.flatMapLatest {
        getProfileDataUseCase(_targetUserId.value)
    }

    private val profileContentFlow = combine(
        profileDataFlow.onEach { data ->
            data?.let {
                diagnosticLogger.info(DiagnosticCategory.PROFILE, "PROFILE_LOADED", mapOf("isSelf" to it.isSelf, "publishedCount" to it.publishedArtifacts.size))
            }
        },
        userProfileManager.activeSigilConfig,
        _selectedTab,
        _logoutState,
        _message,
        _isActionLoading,
        _isRefreshing,
        _editingId,
        _titleInput
    ) { params ->
        val data = params[0] as ProfileData?
        val sigilConfig = params[1] as SigilConfig
        val selectedTab = params[2] as ProfileTab
        val logoutState = params[3] as LogoutState
        val message = params[4] as UiText?
        val isActionLoading = params[5] as Boolean
        val isRefreshing = params[6] as Boolean
        val editingId = params[7] as String?
        val titleInput = params[8] as String?

        fun mapArtifact(artifact: Artifact): Artifact {
            return if (artifact.id == editingId && (titleInput != null)) {
                artifact.copy(title = titleInput)
            } else {
                artifact
            }
        }

        val mappedLocalDrafts = data?.localDrafts?.map { draft ->
            val author = data.userProfile?.let { AuthorSnapshot.fromUser(it) } 
                ?: AuthorSnapshot(name = "Private Draft")
            
            val artifact = draftMapper.map(
                draft = draft,
                author = author,
                fallbackTitle = "Unfinished Recording"
            )
            
            DraftUiModel(
                artifact = mapArtifact(artifact),
                reviewProgress = draft.reviewProgress,
                isListened = draft.lifecycle == com.saurabh.artifact.model.ArtifactLifecycle.READY_TO_PUBLISH
            )
        } ?: emptyList()

        val mappedPublished = data?.publishedArtifacts?.map { mapArtifact(it) } ?: emptyList()
        val mappedCloudDrafts = data?.cloudDrafts?.map { mapArtifact(it) } ?: emptyList()
        val mappedSaved = data?.savedArtifacts?.map { mapArtifact(it) } ?: emptyList()

        ProfileContent(
            data = data,
            sigilConfig = sigilConfig,
            selectedTab = selectedTab,
            logoutState = logoutState,
            message = message,
            isActionLoading = isActionLoading,
            isRefreshing = isRefreshing,
            mappedLocalDrafts = mappedLocalDrafts,
            mappedPublishedArtifacts = mappedPublished,
            mappedCloudDrafts = mappedCloudDrafts,
            mappedSavedArtifacts = mappedSaved
        )
    }

    private val playbackStateFlow = combine(
        playbackCoordinator.currentArtifact,
        playbackCoordinator.isPlaying,
        playbackCoordinator.isBuffering,
        playbackCoordinator.currentPosition,
        playbackCoordinator.duration,
    ) { currentlyPlaying, isPlaying, isBuffering, currentPosition, duration ->
        PlaybackState(
            currentlyPlaying = currentlyPlaying,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            position = currentPosition,
            duration = duration
        )
    }

    val uiState: StateFlow<ProfileUiState> = combine(
        profileContentFlow,
        playbackStateFlow
    ) { content, playback ->
        val data = content.data
        val state = ProfileUiState(
            userProfile = data?.userProfile,
            sigilConfig = content.sigilConfig,
            isSelf = data?.isSelf ?: true,
            isResonating = data?.isResonating ?: false,
            selectedTab = content.selectedTab,
            publishedArtifacts = content.mappedPublishedArtifacts,
            cloudDrafts = content.mappedCloudDrafts,
            savedArtifacts = content.mappedSavedArtifacts,
            localDrafts = content.mappedLocalDrafts,
            logoutState = content.logoutState,
            message = content.message,
            isLoading = data == null,
            isActionLoading = content.isActionLoading,
            isRefreshing = content.isRefreshing,
            currentlyPlayingArtifact = playback.currentlyPlaying,
            isPlaying = playback.isPlaying,
            isBuffering = playback.isBuffering,
            currentPosition = playback.position.inWholeMilliseconds,
            durationMs = playback.duration.inWholeMilliseconds,
        )



        state
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5.seconds),
        initialValue = ProfileUiState(),
    )

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshTrigger.value += 1
            delay(800.milliseconds)
            _isRefreshing.value = false
        }
    }

    fun selectTab(tab: ProfileTab) {
        _selectedTab.value = tab
    }

    fun setTargetUser(userId: String?) {
        _targetUserId.value = userId
        _refreshTrigger.value += 1
    }

    fun toggleResonance() {
        val targetId = _targetUserId.value ?: return
        val currentId = currentUserId ?: return
        if (targetId == currentId) return



        viewModelScope.launch {
            _isActionLoading.value = true
            profileInteractionUseCase.toggleResonance(currentId, targetId, uiState.value.isResonating)
                .onSuccess {
                    _message.value = if (uiState.value.isResonating) 
                        UiText.StringResource(R.string.unfollowed) 
                    else 
                        UiText.StringResource(R.string.now_following)
                }
                .onFailure { e ->
                    _message.value = ErrorMessageMapper.map(e)
                }
            _isActionLoading.value = false
        }
    }

    fun playAudio(artifact: Artifact) {
        if (artifact.audioUrl.isEmpty()) {
            _message.value = UiText.StringResource(R.string.no_voice_yet)
            return
        }

        playbackCoordinator.playArtifact(artifact)
    }

    fun togglePlayback() {
        playbackCoordinator.togglePlayPause()
    }

    fun toggleSave(artifact: Artifact) {
        val isSaved = savedIds.value.contains(artifact.id)
        savedArtifactManager.toggleSave(artifact)
        if (!isSaved) {
            _message.value = UiText.StringResource(R.string.saved_to_journey)
        } else {
            _message.value = UiText.StringResource(R.string.removed_from_journey)
        }
    }

    fun renameDraft(draftId: String, newTitle: String) {
        // Update local buffer immediately for zero-latency UI
        _editingId.value = draftId
        _titleInput.value = newTitle

        renameDebounceJob?.cancel()
        renameDebounceJob = viewModelScope.launch {
            delay(500.milliseconds)
            
            diagnosticLogger.info(DiagnosticCategory.PROFILE, "DRAFT_RENAME_STARTED", mapOf(LogKeys.DRAFT_ID to draftId, "newTitle" to newTitle))
            profileInteractionUseCase.renameDraft(draftId, newTitle)
                .onSuccess {
                    _message.value = UiText.StringResource(R.string.draft_renamed)
                    // Clear buffer ONLY after successful persistence to let Room take authority back
                    if (_editingId.value == draftId) {
                        _editingId.value = null
                        _titleInput.value = null
                    }
                }
                .onFailure { e ->
                    _message.value = ErrorMessageMapper.map(e)
                    // Also clear on failure to revert to Room truth
                    if (_editingId.value == draftId) {
                        _editingId.value = null
                        _titleInput.value = null
                    }
                }
        }
    }

    fun deleteDraft(draftId: String) {
        diagnosticLogger.info(DiagnosticCategory.PROFILE, "DRAFT_DELETE_STARTED", mapOf(LogKeys.DRAFT_ID to draftId))
        viewModelScope.launch {
            _isActionLoading.value = true
            profileInteractionUseCase.deleteDraft(draftId)
                .onSuccess {
                    diagnosticLogger.info(DiagnosticCategory.PROFILE, "DRAFT_DELETE_SUCCESS", mapOf(LogKeys.DRAFT_ID to draftId))
                    _message.value = UiText.StringResource(R.string.draft_deleted)
                }
                .onFailure { e ->
                    diagnosticLogger.error(DiagnosticCategory.PROFILE, "DRAFT_DELETE_FAILED", mapOf(LogKeys.DRAFT_ID to draftId), e)
                    _message.value = ErrorMessageMapper.map(e)
                }
            _isActionLoading.value = false
        }
    }

    fun renamePublishedArtifact(artifactId: String, newTitle: String) {
        // Update local buffer immediately for zero-latency UI
        _editingId.value = artifactId
        _titleInput.value = newTitle

        renameDebounceJob?.cancel()
        renameDebounceJob = viewModelScope.launch {
            delay(500.milliseconds)
            
            diagnosticLogger.info(DiagnosticCategory.PROFILE, "ARTIFACT_RENAME_STARTED", mapOf(LogKeys.ARTIFACT_ID to artifactId, "newTitle" to newTitle))
            profileInteractionUseCase.renamePublishedArtifact(artifactId, newTitle)
                .onSuccess {
                    _message.value = UiText.StringResource(R.string.reflection_renamed)
                    // Clear buffer ONLY after successful persistence to let Room take authority back
                    if (_editingId.value == artifactId) {
                        _editingId.value = null
                        _titleInput.value = null
                    }
                }
                .onFailure { e ->
                    _message.value = ErrorMessageMapper.map(e)
                    // Also clear on failure to revert to Room truth
                    if (_editingId.value == artifactId) {
                        _editingId.value = null
                        _titleInput.value = null
                    }
                }
        }
    }

    fun deletePublishedArtifact(artifactId: String) {
        diagnosticLogger.info(DiagnosticCategory.PROFILE, "ARTIFACT_DELETE_STARTED", mapOf(LogKeys.ARTIFACT_ID to artifactId))
        viewModelScope.launch {
            _isActionLoading.value = true
            profileInteractionUseCase.deletePublishedArtifact(artifactId)
                .onSuccess {
                    diagnosticLogger.info(DiagnosticCategory.PROFILE, "ARTIFACT_DELETE_SUCCESS", mapOf(LogKeys.ARTIFACT_ID to artifactId))
                    _message.value = UiText.StringResource(R.string.reflection_deleted)
                }
                .onFailure { e ->
                    diagnosticLogger.error(DiagnosticCategory.PROFILE, "ARTIFACT_DELETE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
                    _message.value = ErrorMessageMapper.map(e)
                }
            _isActionLoading.value = false
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun logout() {
        viewModelScope.launch {
            _logoutState.value = LogoutState.Loading
            logoutCoordinator.executeLogout()
                .onSuccess {
                    _logoutState.value = LogoutState.Success
                }
                .onFailure { e ->
                    _logoutState.value = LogoutState.Error(ErrorMessageMapper.map(e))
                }
        }
    }

    fun resetLogoutState() {
        _logoutState.value = LogoutState.Idle
    }
}


sealed class LogoutState {
    data object Idle : LogoutState()
    data object Loading : LogoutState()
    data object Success : LogoutState()
    data class Error(val message: UiText) : LogoutState()
}
