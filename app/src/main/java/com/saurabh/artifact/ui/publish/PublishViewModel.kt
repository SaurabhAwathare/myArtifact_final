package com.saurabh.artifact.ui.publish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.util.SecureString
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.PublishingResult
import com.saurabh.artifact.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PublishViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val publishArtifactUseCase: PublishArtifactUseCase,
    private val identityScout: IdentityScout,
    private val auth: com.google.firebase.auth.FirebaseAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(PublishUiState())
    val uiState = _uiState.asStateFlow()

    fun loadDraft(draftId: String) {
        viewModelScope.launch {
            recordingRepository.observeDraft(draftId).collect { draft ->
                if (draft != null) {
                    _uiState.update { state ->
                        state.copy(
                            draft = draft,
                            title = state.title.ifBlank { draft.title ?: "" },
                            emotion = state.emotion ?: draft.emotion
                        )
                    }
                }
            }
        }
    }

    fun updateTitle(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun updateEmotion(emotion: Emotion) {
        _uiState.update { it.copy(emotion = emotion) }
    }

    fun onPublishClick() {
        val title = _uiState.value.title
        val realName = auth.currentUser?.displayName?.let { SecureString.fromString(it) }
        val email = auth.currentUser?.email?.let { SecureString.fromString(it) }

        val warnings = identityScout.detectLeaks(title, realName, email)
        
        realName?.clear()
        email?.clear()

        if (warnings.isNotEmpty()) {
            _uiState.update { it.copy(
                showPrivacyNudge = true,
                privacyWarnings = warnings.map { w -> w.message }
            ) }
        } else {
            performPublish()
        }
    }

    fun dismissPrivacyNudge() {
        _uiState.update { it.copy(showPrivacyNudge = false) }
    }

    fun confirmPublishAnyway() {
        _uiState.update { it.copy(showPrivacyNudge = false) }
        performPublish()
    }

    private fun performPublish() {
        val draft = _uiState.value.draft ?: return
        if (_uiState.value.title.isBlank() || _uiState.value.emotion == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true) }
            
            // Save metadata first
            recordingRepository.updateDraft(draft.copy(
                title = _uiState.value.title,
                emotion = _uiState.value.emotion
            ))

            publishArtifactUseCase(draft.localAudioPath)
                .onSuccess { result ->
                    _uiState.update { it.copy(
                        isPublishing = false, 
                        isSuccess = true,
                        isQueuedOffline = result == PublishingResult.QUEUED_OFFLINE
                    ) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isPublishing = false, error = e.message) }
                }
        }
    }
}

data class PublishUiState(
    val draft: ArtifactDraftEntity? = null,
    val title: String = "",
    val emotion: Emotion? = null,
    val isPublishing: Boolean = false,
    val isSuccess: Boolean = false,
    val isQueuedOffline: Boolean = false,
    val error: String? = null,
    val showPrivacyNudge: Boolean = false,
    val privacyWarnings: List<String> = emptyList()
)
