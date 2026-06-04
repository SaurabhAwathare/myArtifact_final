package com.saurabh.artifact.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackType
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.repository.SavedArtifactManager
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ProfileTab(val title: String) {
    PUBLISHED("Published"),
    DRAFTS("Drafts"),
    SAVED("Stayed With Me")
}

data class ProfileUiState(
    val userProfile: User? = null,
    val avatarConfig: AvatarConfig = AvatarConfig(),
    val isSelf: Boolean = true,
    val isResonating: Boolean = false,
    val selectedTab: ProfileTab = ProfileTab.PUBLISHED,
    val publishedArtifacts: List<Artifact> = emptyList(),
    val cloudDrafts: List<Artifact> = emptyList(),
    val savedArtifacts: List<Artifact> = emptyList(),
    val localDrafts: List<ArtifactDraftEntity> = emptyList(),
    val logoutState: LogoutState = LogoutState.Idle,
    val message: UiText? = null,
    val isLoading: Boolean = true,
    val isActionLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    // Playback State (Mirrored from Manager for UI convenience)
    val currentlyPlayingArtifact: Artifact? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0,
    val durationMs: Long = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val userProfileManager: UserProfileManager,
    private val settingsRepository: SettingsRepository,
    private val recordingRepository: RecordingRepository,
    private val reactionRepository: ReactionRepository,
    private val savedArtifactManager: SavedArtifactManager,
    private val playbackCoordinator: PlaybackCoordinator,
    private val reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager
) : ViewModel() {

    val currentUserId: String? get() = authRepository.currentUser.value?.uid
    val savedIds = savedArtifactManager.savedIds

    private val _targetUserId = MutableStateFlow<String?>(null)
    private val _selectedTab = MutableStateFlow(ProfileTab.PUBLISHED)
    private val _logoutState = MutableStateFlow<LogoutState>(LogoutState.Idle)
    private val _message = MutableStateFlow<UiText?>(null)
    private val _isActionLoading = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)
    private val _refreshTrigger = MutableStateFlow(0)

    private val profileDataFlow = combine(
        _targetUserId,
        authRepository.userData,
        _refreshTrigger
    ) { targetId, currentUser, _ ->
        val isSelf = (targetId == null) || (targetId == currentUser?.id)
        val finalId = targetId ?: currentUser?.id
        finalId to isSelf
    }.flatMapLatest { (finalId, isSelf) ->
        val effectiveId = finalId ?: return@flatMapLatest flowOf(null)
        
        combine(
            userRepository.streamUserProfile(effectiveId),
            artifactRepository.getUserArtifacts(effectiveId, onlyActive = !isSelf),
            artifactRepository.getSavedArtifacts(effectiveId),
            if (isSelf) recordingRepository.observeDrafts().map { drafts ->
                drafts.filter { it.lifecycle != com.saurabh.artifact.model.ArtifactLifecycle.PUBLISHED }
            } else flowOf(emptyList()),
            userRepository.observeIsResonating(authRepository.currentUserId, effectiveId)
        ) { profile, allArtifacts, saved, localDrafts, isResonating ->
            val statusPublished = com.saurabh.artifact.model.ArtifactStatus.ACTIVE
            ProfileData(
                userProfile = profile,
                publishedArtifacts = allArtifacts.filter { it.status == statusPublished },
                cloudDrafts = allArtifacts.filter { it.status != statusPublished },
                savedArtifacts = saved,
                localDrafts = localDrafts,
                isResonating = isResonating,
                isSelf = isSelf
            )
        }
    }

    val uiState: StateFlow<ProfileUiState> = combine(
        profileDataFlow,
        userProfileManager.activeAvatarConfig,
        _selectedTab,
        _logoutState,
        _message,
        _isActionLoading,
        _isRefreshing,
        playbackCoordinator.currentArtifact,
        playbackCoordinator.isPlaying,
        playbackCoordinator.isBuffering,
        playbackCoordinator.currentPosition,
        playbackCoordinator.durationMs
    ) { params: Array<Any?> ->
        val data = params[0] as ProfileData?
        val avatarConfig = params[1] as AvatarConfig
        val selectedTab = params[2] as ProfileTab
        val logoutState = params[3] as LogoutState
        val message = params[4] as UiText?
        val isActionLoading = params[5] as Boolean
        val isRefreshing = params[6] as Boolean
        
        val currentlyPlaying = params[7] as Artifact?
        val isPlaying = params[8] as Boolean
        val isBuffering = params[9] as Boolean
        val position = params[10] as Long
        val duration = params[11] as Long

        ProfileUiState(
            userProfile = data?.userProfile,
            avatarConfig = avatarConfig,
            isSelf = data?.isSelf ?: true,
            isResonating = data?.isResonating ?: false,
            selectedTab = selectedTab,
            publishedArtifacts = data?.publishedArtifacts ?: emptyList(),
            cloudDrafts = data?.cloudDrafts ?: emptyList(),
            savedArtifacts = data?.savedArtifacts ?: emptyList(),
            localDrafts = data?.localDrafts ?: emptyList(),
            logoutState = logoutState,
            message = message,
            isLoading = data == null,
            isActionLoading = isActionLoading,
            isRefreshing = isRefreshing,
            currentlyPlayingArtifact = currentlyPlaying,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentPosition = position,
            durationMs = duration
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProfileUiState()
    )

    init {
        android.util.Log.d("ReviewDebug", "ProfileViewModel initialized")
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshTrigger.value += 1
            // Small delay to make the refresh feel deliberate and "calm"
            delay(800)
            _isRefreshing.value = false
        }
    }

    private fun clearUnused() {
        // Removed observeState
    }

    fun selectTab(tab: ProfileTab) {
        _selectedTab.value = tab
    }

    fun setTargetUser(userId: String?) {
        _targetUserId.value = userId
    }

    fun toggleResonance() {
        val targetId = _targetUserId.value ?: return
        val currentId = authRepository.currentUserId ?: return
        if (targetId == currentId) return

        viewModelScope.launch {
            _isActionLoading.value = true
            try {
                if (uiState.value.isResonating) {
                    userRepository.stopResonatingWithUser(currentId, targetId)
                    _message.value = UiText.StringResource(R.string.unfollowed)
                } else {
                    userRepository.resonateWithUser(currentId, targetId)
                    _message.value = UiText.StringResource(R.string.now_following)
                }
            } catch (e: Exception) {
                _message.value = ErrorMessageMapper.map(e)
            } finally {
                _isActionLoading.value = false
            }
        }
    }

    fun playAudio(artifact: Artifact) {
        if (artifact.audioUrl.isEmpty()) {
            _message.value = UiText.StringResource(R.string.no_voice_yet)
            return
        }
        playbackCoordinator.playArtifact(artifact)
    }

    fun playDraft(draft: ArtifactDraftEntity) {
        playbackCoordinator.playDraftPreview(draft.id)
    }

    fun togglePlayback() {
        playbackCoordinator.togglePlayPause()
    }

    fun reactToArtifact(artifactId: String, type: ReactionType) {
        val userId = authRepository.currentUser.value?.uid ?: return
        viewModelScope.launch {
            reactionRepository.toggleReaction(artifactId, userId, type)
        }
    }

    fun updateArtifactVisibility(artifactId: String, mode: com.saurabh.artifact.model.ReactionVisibilityMode) {
        viewModelScope.launch {
            reactionRepository.setVisibilityMode(artifactId, mode)
        }
    }

    // --- Ownership Management ---

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
        viewModelScope.launch {
            _isActionLoading.value = true
            try {
                recordingRepository.renameDraft(draftId, newTitle)
                _message.value = UiText.StringResource(R.string.draft_renamed)
            } catch (e: Exception) {
                _message.value = ErrorMessageMapper.map(e)
            } finally {
                _isActionLoading.value = false
            }
        }
    }

    fun deleteDraft(draftId: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            try {
                recordingRepository.getDraft(draftId)?.let { draft ->
                    recordingRepository.deleteDraft(draft)
                    _message.value = UiText.StringResource(R.string.draft_deleted)
                }
            } catch (e: Exception) {
                _message.value = ErrorMessageMapper.map(e)
            } finally {
                _isActionLoading.value = false
            }
        }
    }

    fun renamePublishedArtifact(artifactId: String, newTitle: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            artifactRepository.renamePublishedArtifact(artifactId, newTitle)
                .onSuccess {
                    _message.value = UiText.StringResource(R.string.reflection_renamed)
                }
                .onFailure { e ->
                    _message.value = ErrorMessageMapper.map(e)
                }
            _isActionLoading.value = false
        }
    }

    fun deletePublishedArtifact(artifactId: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            artifactRepository.deletePublishedArtifact(artifactId)
                .onSuccess {
                    _message.value = UiText.StringResource(R.string.reflection_deleted)
                }
                .onFailure { e ->
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
            settingsRepository.signOut()
                .onSuccess {
                    _logoutState.value = LogoutState.Success
                }
                .onFailure { e ->
                    _logoutState.value = LogoutState.Error(ErrorMessageMapper.map(e))
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Playback ownership is now handled by the Coordinator.
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

private data class ProfileData(
    val userProfile: User?,
    val publishedArtifacts: List<Artifact>,
    val cloudDrafts: List<Artifact>,
    val savedArtifacts: List<Artifact>,
    val localDrafts: List<ArtifactDraftEntity>,
    val isResonating: Boolean,
    val isSelf: Boolean
)
