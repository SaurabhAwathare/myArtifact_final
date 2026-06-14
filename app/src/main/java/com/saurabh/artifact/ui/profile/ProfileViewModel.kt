package com.saurabh.artifact.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.*
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
    val avatarConfig: AvatarConfig = AvatarConfig(),
    val isSelf: Boolean = false,
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
    val currentlyPlayingArtifact: Artifact? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0,
    val durationMs: Long = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    userProfileManager: UserProfileManager,
    private val settingsRepository: SettingsRepository,
    private val savedArtifactManager: SavedArtifactManager,
    private val playbackCoordinator: PlaybackCoordinator,
    getProfileDataUseCase: com.saurabh.artifact.domain.profile.GetProfileDataUseCase,
    private val profileInteractionUseCase: com.saurabh.artifact.domain.profile.ProfileInteractionUseCase,
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

    private val profileDataFlow = _refreshTrigger.flatMapLatest {
        getProfileDataUseCase(_targetUserId.value)
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
        playbackCoordinator.duration,
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
        val position = params[10] as Duration
        val duration = params[11] as Duration

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
            currentPosition = position.inWholeMilliseconds,
            durationMs = duration.inWholeMilliseconds,
        )
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
        
        // If it's a draft, handle review logic
        if (artifact.isDraft) {
             // Redundant since playAudio is for published list, 
             // but keeping it robust for the union lists
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
        viewModelScope.launch {
            _isActionLoading.value = true
            profileInteractionUseCase.renameDraft(draftId, newTitle)
                .onSuccess {
                    _message.value = UiText.StringResource(R.string.draft_renamed)
                }
                .onFailure { e ->
                    _message.value = ErrorMessageMapper.map(e)
                }
            _isActionLoading.value = false
        }
    }

    fun deleteDraft(draftId: String) {
        android.util.Log.d("ProfileViewModel", "User initiated delete for draft: $draftId")
        viewModelScope.launch {
            _isActionLoading.value = true
            profileInteractionUseCase.deleteDraft(draftId)
                .onSuccess {
                    android.util.Log.d("ProfileViewModel", "Delete success for draft: $draftId")
                    _message.value = UiText.StringResource(R.string.draft_deleted)
                }
                .onFailure { e ->
                    android.util.Log.e("ProfileViewModel", "Delete failed for draft: $draftId", e)
                    _message.value = ErrorMessageMapper.map(e)
                }
            _isActionLoading.value = false
        }
    }

    fun renamePublishedArtifact(artifactId: String, newTitle: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            profileInteractionUseCase.renamePublishedArtifact(artifactId, newTitle)
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
        android.util.Log.d("ProfileViewModel", "User initiated delete for artifact: $artifactId")
        viewModelScope.launch {
            _isActionLoading.value = true
            profileInteractionUseCase.deletePublishedArtifact(artifactId)
                .onSuccess {
                    android.util.Log.d("ProfileViewModel", "Delete success for artifact: $artifactId")
                    _message.value = UiText.StringResource(R.string.reflection_deleted)
                }
                .onFailure { e ->
                    android.util.Log.e("ProfileViewModel", "Delete failed for artifact: $artifactId", e)
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
