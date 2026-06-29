package com.saurabh.artifact.ui.feed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.saurabh.artifact.audio.ReviewAuthorityService
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.CommentRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.domain.review.GetEngagementStateUseCase
import com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
import com.saurabh.artifact.security.UploadGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommentUiState(
    val isSubmitting: Boolean = false,
    val submissionSuccess: Boolean = false,
    val errorMessage: String? = null,
    val engagementStatus: EngagementStatus = EngagementStatus.LOCKED,
    val listeningProgress: Float = 0f,
    val currentUserId: String = "",
    val requiredCoverage: Float = 0.95f,
)

@Suppress("unused")
@HiltViewModel
class CommentViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: CommentRepository,
    private val artifactRepository: ArtifactRepository,
    private val auth: AuthRepository,
    private val userRepository: UserRepository,
    private val getEngagementStateUseCase: GetEngagementStateUseCase,
    private val reviewAuthorityService: ReviewAuthorityService,
    private val uploadGuard: UploadGuard,
    private val commentUnlockPolicy: CommentUnlockPolicy
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommentUiState())
    val uiState: StateFlow<CommentUiState> = _uiState.asStateFlow()

    private val _artifactIdForPaging = MutableStateFlow<Pair<String, String>?>(null)
    private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    val commentsPager: Flow<PagingData<ArtifactComment>> = combine(
        _artifactIdForPaging,
        _refreshTrigger.onStart { emit(Unit) }
    ) { pair, _ -> pair }
        .flatMapLatest { pair ->
            if (pair == null) {
                flowOf(PagingData.empty())
            } else {
                repository.getCommentsPager(pair.first, auth.currentUserId, pair.second)
            }
        }
        .cachedIn(viewModelScope)

    fun reset() {
        _uiState.value = CommentUiState()
        _artifactIdForPaging.value = null
    }

    /**
     * Submits the recorded text reflection.
     */
    fun submitReflection(
        artifactId: String, 
        content: String, 
        visibility: VisibilityLayer, 
        authorType: AuthorType,
        revealAt: com.google.firebase.Timestamp? = null
    ) {
        if (content.isBlank()) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            
            userRepository.getOrCreateProfile()
                .onSuccess { result ->
                    val user = result.user
                    val resultSubmission = repository.submitReflection(
                        artifactId = artifactId,
                        userId = auth.currentUserId,
                        content = content,
                        visibility = visibility,
                        authorType = authorType,
                        revealAt = revealAt,
                        authorName = if (authorType == AuthorType.PSEUDONYM) user.anonymousName else "Quiet Presence",
                        authorAvatarSeed = if (authorType == AuthorType.PSEUDONYM) user.avatarSeed else "ANONYMOUS_AURA"
                    )

                    if (resultSubmission.isSuccess) {
                        _uiState.update { it.copy(isSubmitting = false, submissionSuccess = true) }
                    } else {
                        _uiState.update { it.copy(isSubmitting = false, errorMessage = resultSubmission.exceptionOrNull()?.message) }
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = e.message) }
                }
        }
    }

    fun loadComments(artifactId: String, ownerId: String) {
        android.util.Log.d("ReviewDebug", "loadComments called for artifactId=$artifactId")
        _artifactIdForPaging.value = artifactId to ownerId
        
        viewModelScope.launch {
            val engagementFlow = getEngagementStateUseCase.execute(artifactId)
            val sessionFlow = reviewAuthorityService.currentProgress

            combine(
                engagementFlow,
                sessionFlow
            ) { status, progress ->
                val currentProgress = if (progress?.artifactId == artifactId) {
                    if (progress.durationMs > 0) progress.evidence.furthestPositionMs.toFloat() / progress.durationMs else 0f
                } else 0f

                object {
                    val status = status
                    val progress = currentProgress
                    val currentUserId = auth.currentUserId
                }
            }.collect { update ->
                if (update.status == EngagementStatus.VERIFYING && _uiState.value.engagementStatus == EngagementStatus.LOCKED) {
                    com.saurabh.artifact.util.ArtifactLogger.i("CommentViewModel", "Listening threshold reached for ${artifactId}, starting verification.")
                }

                if (update.status == EngagementStatus.UNLOCKED && 
                    (_uiState.value.engagementStatus == EngagementStatus.LOCKED || 
                     _uiState.value.engagementStatus == EngagementStatus.VERIFYING)) {
                    com.saurabh.artifact.util.ArtifactLogger.i("CommentViewModel", "Artifact unlocked for ${artifactId}, refreshing comments.")
                    _refreshTrigger.tryEmit(Unit)
                }

                _uiState.update { 
                    it.copy(
                        engagementStatus = update.status,
                        listeningProgress = update.progress,
                        currentUserId = update.currentUserId,
                        requiredCoverage = commentUnlockPolicy.minCoverage
                    )
                }
            }
        }
    }

    fun submitReport(artifactId: String, commentId: String?, reason: ReportReason, details: String) {
        viewModelScope.launch {
            val deviceId = uploadGuard.getDeviceFingerprint().hashCode()
            artifactRepository.submitReport(
                artifactId = artifactId,
                reason = reason,
                details = details,
                deviceId = deviceId,
                commentId = commentId
            ).onSuccess {
                // Trigger a refresh of the comments pager
                _refreshTrigger.tryEmit(Unit)
            }
        }
    }

    /**
     * Updates the creator's reaction to a specific reflection.
     */
    fun reactToComment(commentId: String, type: ReactionType) {
        viewModelScope.launch {
            repository.reactToComment(commentId, type)
        }
    }
}
