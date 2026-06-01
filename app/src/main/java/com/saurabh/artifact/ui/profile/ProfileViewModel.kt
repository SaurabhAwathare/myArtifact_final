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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ProfileTab(val title: String) {
    PUBLISHED("Published"),
    DRAFTS("Drafts"),
    SAVED("Archive")
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
    val message: String? = null,
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
    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProfileUiState> = combine(
        _targetUserId,
        userProfileManager.activeAvatarConfig,
        authRepository.userData,
        _selectedTab,
        _logoutState,
        _message,
        playbackCoordinator.currentArtifact,
        playbackCoordinator.isPlaying,
        playbackCoordinator.isBuffering,
        playbackCoordinator.currentPosition,
        playbackCoordinator.durationMs
    ) { params: Array<Any?> ->
        val targetId = params[0] as String?
        val avatarConfig = params[1] as AvatarConfig
        val currentUser = params[2] as com.saurabh.artifact.model.User?
        val selectedTab = params[3] as ProfileTab
        val logoutState = params[4] as LogoutState
        val message = params[5] as String?
        
        val currentlyPlaying = params[6] as Artifact?
        val isPlaying = params[7] as Boolean
        val isBuffering = params[8] as Boolean
        val position = params[9] as Long
        val duration = params[10] as Long

        val isSelf = (targetId == null) || (targetId == currentUser?.id)
        val finalId = targetId ?: currentUser?.id

        // Note: These nested flows will be resolved in flatMapLatest below
        finalId to ProfileUiState(
            avatarConfig = avatarConfig,
            isSelf = isSelf,
            selectedTab = selectedTab,
            logoutState = logoutState,
            message = message,
            currentlyPlayingArtifact = currentlyPlaying,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentPosition = position,
            durationMs = duration
        )
    }.flatMapLatest { (finalId, baseState) ->
        val effectiveId = finalId ?: return@flatMapLatest flowOf(baseState)
        
        combine(
            userRepository.streamUserProfile(effectiveId),
            artifactRepository.getUserArtifacts(effectiveId),
            artifactRepository.getSavedArtifacts(effectiveId),
            recordingRepository.observeDrafts(),
            userRepository.observeIsResonating(authRepository.currentUserId, effectiveId)
        ) { profile, allArtifacts, saved, localDrafts, isResonating ->
            baseState.copy(
                userProfile = profile,
                publishedArtifacts = allArtifacts.filter { !it.isDraft },
                cloudDrafts = allArtifacts.filter { it.isDraft },
                savedArtifacts = saved,
                localDrafts = localDrafts,
                isResonating = isResonating
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProfileUiState()
    )

    init {
        android.util.Log.d("ReviewDebug", "ProfileViewModel initialized")
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
        val currentId = authRepository.currentUser.value?.uid ?: return
        if (targetId == currentId) return

        viewModelScope.launch {
            if (uiState.value.isResonating) {
                userRepository.stopResonatingWithUser(currentId, targetId)
            } else {
                userRepository.resonateWithUser(currentId, targetId)
            }
        }
    }

    fun playAudio(artifact: Artifact) {
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
        savedArtifactManager.toggleSave(artifact)
    }

    fun renameDraft(draftId: String, newTitle: String) {
        viewModelScope.launch {
            recordingRepository.renameDraft(draftId, newTitle)
        }
    }

    fun deleteDraft(draftId: String) {
        viewModelScope.launch {
            recordingRepository.getDraft(draftId)?.let { draft ->
                recordingRepository.deleteDraft(draft)
            }
        }
    }

    fun renamePublishedArtifact(artifactId: String, newTitle: String) {
        viewModelScope.launch {
            artifactRepository.renamePublishedArtifact(artifactId, newTitle)
        }
    }

    fun deletePublishedArtifact(artifactId: String) {
        viewModelScope.launch {
            artifactRepository.deletePublishedArtifact(artifactId)
                .onSuccess {
                    _message.value = "Artifact deleted successfully"
                }
                .onFailure { e ->
                    _message.value = "Failed to delete: ${e.message}"
                }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun logout() {
        viewModelScope.launch {
            _logoutState.value = LogoutState.Loading
            authRepository.signOut()
                .onSuccess {
                    _logoutState.value = LogoutState.Success
                }
                .onFailure { e ->
                    _logoutState.value = LogoutState.Error(e.message ?: "Unknown error")
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
    data class Error(val message: String) : LogoutState()
}
