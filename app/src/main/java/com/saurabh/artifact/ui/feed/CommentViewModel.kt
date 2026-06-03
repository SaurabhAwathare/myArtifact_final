package com.saurabh.artifact.ui.feed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.AuthorType
import com.saurabh.artifact.model.VisibilityLayer
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.CommentRepository
import com.saurabh.artifact.repository.UserRepository
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
    val isLocked: Boolean = true,
    val hasCompletedReview: Boolean = false,
    val listeningProgress: Float = 0f,
    val currentUserId: String = "",
)

@Suppress("unused")
@HiltViewModel
class CommentViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: CommentRepository,
    private val artifactRepository: ArtifactRepository,
    private val auth: AuthRepository,
    private val userRepository: UserRepository,
    private val commentUnlockRepository: com.saurabh.artifact.repository.CommentUnlockRepository,
    private val reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager,
    private val uploadGuard: com.saurabh.artifact.security.UploadGuard
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
            
            val user = userRepository.getOrCreateProfile()
            
            val result = repository.submitReflection(
                artifactId = artifactId,
                userId = auth.currentUserId,
                content = content,
                visibility = visibility,
                authorType = authorType,
                revealAt = revealAt,
                authorName = if (authorType == AuthorType.PSEUDONYM) user.anonymousName else "Quiet Presence",
                authorAvatarSeed = if (authorType == AuthorType.PSEUDONYM) user.avatarSeed else "ANONYMOUS_AURA"
            )

            if (result.isSuccess) {
                _uiState.update { it.copy(isSubmitting = false, submissionSuccess = true) }
            } else {
                _uiState.update { it.copy(isSubmitting = false, errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun loadComments(artifactId: String, ownerId: String) {
        android.util.Log.d("ReviewDebug", "loadComments called for artifactId=$artifactId")
        _artifactIdForPaging.value = artifactId to ownerId
        
        viewModelScope.launch {
            val unlockFlow = commentUnlockRepository.isUnlocked(artifactId)
                .onStart { android.util.Log.d("ReviewDebug", "unlockFlow started") }
            val sessionFlow = reviewSessionManager.reviewProgress
                .onStart { android.util.Log.d("ReviewDebug", "sessionFlow started") }

            combine(
                unlockFlow,
                sessionFlow
            ) { isUnlocked, session ->
                android.util.Log.d("ReviewDebug", "Flow emitted: repoIsUnlocked=$isUnlocked, sessionArtifactId=${session.artifactId}")
                
                val isThresholdMetForThisArtifact = (session.artifactId == artifactId) && session.isThresholdMet
                
                // Persistence side-effect
                if (isThresholdMetForThisArtifact && !isUnlocked) {
                    android.util.Log.d("ReviewDebug", "Triggering unlock side-effect for $artifactId")
                    viewModelScope.launch {
                        commentUnlockRepository.unlockArtifact(artifactId)
                    }
                }

                val finalIsUnlocked = isUnlocked || isThresholdMetForThisArtifact

                android.util.Log.d("ReviewDebug", "Final calculated unlock state for UI: $finalIsUnlocked (repo=$isUnlocked, sessionMet=$isThresholdMetForThisArtifact)")

                object {
                    val isUnlocked = finalIsUnlocked
                    val hasCompletedReview = isThresholdMetForThisArtifact
                    val progress = if (session.artifactId == artifactId) session.progress else 0f
                    val currentUserId = auth.currentUserId
                }
            }.collect { update ->
                android.util.Log.d("ReviewDebug", "Collecting update in UI State: isLocked=${!update.isUnlocked}, progress=${update.progress}")

                _uiState.update { 
                    it.copy(
                        isLocked = !update.isUnlocked,
                        hasCompletedReview = update.hasCompletedReview,
                        listeningProgress = update.progress,
                        currentUserId = update.currentUserId
                    )
                }
            }
        }
    }

    fun submitReport(artifactId: String, commentId: String?, reason: com.saurabh.artifact.model.ReportReason, details: String) {
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
    fun reactToComment(commentId: String, type: com.saurabh.artifact.model.ReactionType) {
        viewModelScope.launch {
            repository.reactToComment(commentId, type)
        }
    }
}
