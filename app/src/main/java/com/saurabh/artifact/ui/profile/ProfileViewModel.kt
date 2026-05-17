package com.saurabh.artifact.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.AudioPlayer
import com.saurabh.artifact.audio.PlaybackSessionManager
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.*
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    userProfileManager: UserProfileManager,
    private val recordingRepository: RecordingRepository,
    private val reactionRepository: ReactionRepository,
    private val playbackSessionManager: PlaybackSessionManager
) : ViewModel() {

    val currentUserId: String? get() = authRepository.currentUser.value?.uid

    private val _targetUserId = MutableStateFlow<String?>(null)

    init {
        android.util.Log.d("ReviewDebug", "ProfileViewModel initialized")
    }

    val isSelf: StateFlow<Boolean> = _targetUserId.map { id ->
        (id == null) || (id == authRepository.currentUser.value?.uid)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val identityEmoji: StateFlow<String> = userProfileManager.activeUserEmoji
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "✨"
        )

    val userProfile: StateFlow<User?> = _targetUserId
        .flatMapLatest { id ->
            val finalId = id ?: authRepository.currentUser.value?.uid
            if (finalId != null) {
                userRepository.streamUserProfile(finalId)
            } else {
                flowOf(null)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _selectedTab = MutableStateFlow(ProfileTab.PUBLISHED)
    val selectedTab: StateFlow<ProfileTab> = _selectedTab.asStateFlow()

    fun selectTab(tab: ProfileTab) {
        _selectedTab.value = tab
    }

    val userArtifacts: StateFlow<List<Artifact>> = _targetUserId
        .flatMapLatest { id ->
            val finalId = id ?: authRepository.currentUser.value?.uid
            if (finalId != null) {
                artifactRepository.getUserArtifacts(finalId)
            } else {
                flowOf(emptyList())
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val publishedArtifacts: StateFlow<List<Artifact>> = userArtifacts
        .map { list -> list.filter { !it.isDraft } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val cloudDrafts: StateFlow<List<Artifact>> = userArtifacts
        .map { list -> list.filter { it.isDraft } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val likedArtifacts: StateFlow<List<Artifact>> = _targetUserId
        .flatMapLatest { id ->
            val finalId = id ?: authRepository.currentUser.value?.uid
            if (finalId != null) {
                artifactRepository.getLikedArtifacts(finalId)
            } else {
                flowOf(emptyList())
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val drafts: StateFlow<List<ArtifactDraftEntity>> = isSelf
        .flatMapLatest { self ->
            if (self) {
                recordingRepository.observeDrafts()
            } else {
                flowOf(emptyList())
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    val currentlyPlayingArtifact: StateFlow<Artifact?> = playbackSessionManager.currentArtifact
    val isPlaying: StateFlow<Boolean> = playbackSessionManager.isPlaying
    val isBuffering: StateFlow<Boolean> = playbackSessionManager.isBuffering
    val currentPosition: StateFlow<Long> = playbackSessionManager.currentPosition
    val duration: StateFlow<Long> = playbackSessionManager.duration

    fun setTargetUser(userId: String?) {
        _targetUserId.value = userId
        checkFollowState()
    }

    private fun checkFollowState() {
        val targetId = _targetUserId.value ?: return
        val currentId = authRepository.currentUser.value?.uid ?: return
        if (targetId == currentId) return

        viewModelScope.launch {
            _isFollowing.value = userRepository.isFollowing(currentId, targetId)
        }
    }

    fun toggleFollow() {
        val targetId = _targetUserId.value ?: return
        val currentId = authRepository.currentUser.value?.uid ?: return
        if (targetId == currentId) return

        viewModelScope.launch {
            if (_isFollowing.value) {
                userRepository.unfollowUser(currentId, targetId)
                    .onSuccess { _isFollowing.value = false }
            } else {
                userRepository.followUser(currentId, targetId)
                    .onSuccess { _isFollowing.value = true }
            }
        }
    }

    fun playAudio(artifact: Artifact) {
        playbackSessionManager.playArtifact(artifact)
    }

    fun playDraft(draft: ArtifactDraftEntity) {
        val artifact = Artifact(
            id = draft.id,
            title = draft.title ?: "Unfinished Recording",
            audioUrl = draft.localAudioPath,
            amplitudeData = draft.amplitudeData,
            username = userProfile.value?.displayName ?: "Me",
            isDraft = true
        )
        playbackSessionManager.playArtifact(artifact)
    }

    fun togglePlayback() {
        playbackSessionManager.togglePlayback()
    }

    fun reactToArtifact(artifactId: String, type: ReactionType) {
        val userId = authRepository.currentUser.value?.uid ?: return
        viewModelScope.launch {
            artifactRepository.reactToArtifact(artifactId, userId, type)
        }
    }

    fun updateArtifactVisibility(artifactId: String, mode: com.saurabh.artifact.model.ReactionVisibilityMode) {
        viewModelScope.launch {
            reactionRepository.setVisibilityMode(artifactId, mode)
        }
    }

    // --- Ownership Management ---

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
        }
    }

    private val _logoutState = MutableStateFlow<LogoutState>(LogoutState.Idle)
    val logoutState: StateFlow<LogoutState> = _logoutState

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
        playbackSessionManager.stop()
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
