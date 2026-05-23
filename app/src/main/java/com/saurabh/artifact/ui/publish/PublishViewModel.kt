package com.saurabh.artifact.ui.publish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PublishViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val publishArtifactUseCase: PublishArtifactUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(PublishUiState())
    val uiState = _uiState.asStateFlow()

    fun loadDraft(draftId: String) {
        viewModelScope.launch {
            recordingRepository.observeDraft(draftId).collect { draft ->
                if (draft != null) {
                    _uiState.update { it.copy(
                        draft = draft,
                        title = it.title.ifBlank { draft.title ?: "" },
                        emotion = it.emotion ?: try { 
                            draft.emotion?.let { Emotion.valueOf(it) } 
                        } catch (e: Exception) { null }
                    ) }
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

    fun publish() {
        val draft = _uiState.value.draft ?: return
        if (_uiState.value.title.isBlank() || _uiState.value.emotion == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true) }
            
            // Save metadata first
            recordingRepository.updateDraft(draft.copy(
                title = _uiState.value.title,
                emotion = _uiState.value.emotion?.name
            ))

            publishArtifactUseCase(draft.localAudioPath)
                .onSuccess {
                    _uiState.update { it.copy(isPublishing = false, isSuccess = true) }
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
    val error: String? = null
)
