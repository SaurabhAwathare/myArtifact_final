package com.saurabh.artifact.ui.feed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.ReviewAuthorityService
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.CommentRepository
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.domain.review.GetEngagementStateUseCase
import com.saurabh.artifact.domain.review.comments.CommentMerger
import com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
import com.saurabh.artifact.security.UploadGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
    val comments: List<ArtifactComment> = emptyList(),
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
    private val commentUnlockPolicy: CommentUnlockPolicy,
    private val commentMerger: CommentMerger
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommentUiState())
    val uiState: StateFlow<CommentUiState> = _uiState.asStateFlow()

    private var collectionJob: Job? = null
    private val _refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun reset() {
        collectionJob?.cancel()
        _uiState.value = CommentUiState()
    }

    /**
     * Submits the recorded text reflection.
     */
    fun submitReflection(
        artifactId: String, 
        content: String, 
        visibility: VisibilityLayer, 
        authorType: AuthorType
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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadComments(artifactId: String, ownerId: String) {
        android.util.Log.d("ReviewDebug", "loadComments called for artifactId=$artifactId")
        
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            val currentUserId = auth.currentUserId
            val isOwner = currentUserId == ownerId
            
            // Share the engagement flow to avoid multiple subscriptions
            val engagementFlow = getEngagementStateUseCase.execute(artifactId)
                .shareIn(this, SharingStarted.WhileSubscribed(), replay = 1)
            
            val sessionFlow = reviewAuthorityService.currentProgress

            // Update UI State (Status, Progress)
            launch {
                combine(engagementFlow, sessionFlow) { status, progress ->
                    val currentProgress = if (progress?.artifactId == artifactId) {
                        if (progress.durationMs > 0) progress.evidence.furthestPositionMs.toFloat() / progress.durationMs else 0f
                    } else 0f
                    
                    status to currentProgress
                }.collect { (status, progress) ->
                    _uiState.update { 
                        it.copy(
                            engagementStatus = status,
                            listeningProgress = progress,
                            currentUserId = currentUserId,
                            requiredCoverage = commentUnlockPolicy.minCoverage
                        )
                    }
                }
            }

            // Observe and Merge Comments
            launch {
                val ownCommentsFlow = repository.observeOwnComments(artifactId, currentUserId)
                val sharedCommentsFlow = if (isOwner) {
                    repository.observeSharedComments(artifactId)
                } else {
                    engagementFlow.map { it == EngagementStatus.UNLOCKED }
                        .distinctUntilChanged()
                        .flatMapLatest { isUnlocked ->
                            if (isUnlocked) repository.observeSharedComments(artifactId)
                            else flowOf(emptyList())
                        }
                }

                combine(ownCommentsFlow, sharedCommentsFlow) { own, shared ->
                    commentMerger.merge(own, shared)
                }.collect { merged ->
                    _uiState.update { it.copy(comments = merged) }
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
            )
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
