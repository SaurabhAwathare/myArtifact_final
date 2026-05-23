package com.saurabh.artifact.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackSessionManager
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
    SAVED("Saved")
}

data class ProfileUiState(
    val userProfile: User? = null,
    val avatarConfig: AvatarConfig = AvatarConfig(),
    val isSelf: Boolean = true,
    val isFollowing: Boolean = false,
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
    val duration: Long = 0
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
    private val playbackSessionManager: PlaybackSessionManager,
    private val reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager
) : ViewModel() {

    val currentUserId: String? get() = authRepository.currentUser.value?.uid
    val savedIds = savedArtifactManager.savedIds

    private val _targetUserId = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        android.util.Log.d("ReviewDebug", "ProfileViewModel initialized")
        observeState()
    }

    private fun observeState() {
        // 1. Identity & Profile
        viewModelScope.launch {
            combine(
                _targetUserId,
                userProfileManager.activeAvatarConfig,
                authRepository.currentUser
            ) { targetId, config, currentUser ->
                val isSelf = (targetId == null) || (targetId == currentUser?.uid)
                val finalId = targetId ?: currentUser?.uid
                
                Triple(isSelf, config, finalId)
            }.flatMapLatest { (isSelf, config, finalId) ->
                val profileFlow: Flow<User?> = finalId?.let { userRepository.streamUserProfile(it) } ?: flowOf(null)
                profileFlow.map { profile ->
                    _uiState.update { it.copy(
                        isSelf = isSelf,
                        avatarConfig = config,
                        userProfile = profile
                    ) }
                }
            }.collect()
        }

        // 2. Artifacts & Drafts
        viewModelScope.launch {
            _targetUserId.flatMapLatest { id ->
                val finalId = id ?: authRepository.currentUser.value?.uid
                if (finalId != null) {
                    combine(
                        artifactRepository.getUserArtifacts(finalId),
                        artifactRepository.getSavedArtifacts(finalId),
                        recordingRepository.observeDrafts()
                    ) { allArtifacts, saved, localDrafts ->
                        _uiState.update { it.copy(
                            publishedArtifacts = allArtifacts.filter { a -> !a.isDraft },
                            cloudDrafts = allArtifacts.filter { a -> a.isDraft },
                            savedArtifacts = saved,
                            localDrafts = localDrafts
                        ) }
                    }
                } else flowOf(Unit)
            }.collect()
        }

        // 3. Follow State
        viewModelScope.launch {
            _targetUserId.collect { id ->
                val currentId = authRepository.currentUser.value?.uid
                if (id != null && currentId != null && id != currentId) {
                    val following = userRepository.isFollowing(currentId, id)
                    _uiState.update { it.copy(isFollowing = following) }
                }
            }
        }

        // 4. Playback State mirroring
        viewModelScope.launch {
            combine(
                playbackSessionManager.currentArtifact,
                playbackSessionManager.isPlaying,
                playbackSessionManager.isBuffering,
                playbackSessionManager.currentPosition,
                playbackSessionManager.duration
            ) { artifact, playing, buffering, pos, dur ->
                _uiState.update { it.copy(
                    currentlyPlayingArtifact = artifact,
                    isPlaying = playing,
                    isBuffering = buffering,
                    currentPosition = pos,
                    duration = dur
                ) }
            }.collect()
        }
    }

    fun selectTab(tab: ProfileTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setTargetUser(userId: String?) {
        _targetUserId.value = userId
    }

    fun toggleFollow() {
        val targetId = _targetUserId.value ?: return
        val currentId = authRepository.currentUser.value?.uid ?: return
        if (targetId == currentId) return

        viewModelScope.launch {
            if (_uiState.value.isFollowing) {
                userRepository.unfollowUser(currentId, targetId)
                    .onSuccess { _uiState.update { it.copy(isFollowing = false) } }
            } else {
                userRepository.followUser(currentId, targetId)
                    .onSuccess { _uiState.update { it.copy(isFollowing = true) } }
            }
        }
    }

    fun playAudio(artifact: Artifact) {
        reviewSessionManager.startListening(artifact)
    }

    fun playDraft(draft: ArtifactDraftEntity) {
        reviewSessionManager.startReview(draft.id)
    }

    fun togglePlayback() {
        playbackSessionManager.togglePlayPause()
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
                    _uiState.update { it.copy(message = "Artifact deleted successfully") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = "Failed to delete: ${e.message}") }
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(logoutState = LogoutState.Loading) }
            authRepository.signOut()
                .onSuccess {
                    _uiState.update { it.copy(logoutState = LogoutState.Success) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(logoutState = LogoutState.Error(e.message ?: "Unknown error")) }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackSessionManager.stop()
    }

    fun resetLogoutState() {
        _uiState.update { it.copy(logoutState = LogoutState.Idle) }
    }
}


sealed class LogoutState {
    data object Idle : LogoutState()
    data object Loading : LogoutState()
    data object Success : LogoutState()
    data class Error(val message: String) : LogoutState()
}
