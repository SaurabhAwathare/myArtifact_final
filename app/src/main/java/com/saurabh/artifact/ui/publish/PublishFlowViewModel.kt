package com.saurabh.artifact.ui.publish

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.repository.PublishApprovalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class PublishFlowViewModel @Inject constructor(
    private val repository: PublishApprovalRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val draftId: String = savedStateHandle.get<String>("draftId") ?: ""

    private val _uiState = MutableStateFlow(PublishApprovalUiState())
    val uiState: StateFlow<PublishApprovalUiState> = _uiState.asStateFlow()

    init {
        loadDraft()
    }

    private fun loadDraft() {
        if (draftId.isEmpty()) {
            _uiState.update { it.copy(error = "Invalid Draft ID") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val draft = repository.getDraft(draftId)
            if (draft != null) {
                // Assuming transcript is stored in a file or we have a way to get it
                // For now, let's assume it's part of the draft entity or we fetch it
                val transcript = emptyList<TranscriptSegment>() // Placeholder
                
                val validation = repository.validateDraft(draft, transcript)
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        draftId = draftId,
                        title = draft.title ?: "Untitled",
                        emotion = draft.emotion ?: "",
                        tags = draft.tags,
                        transcript = transcript,
                        audioDurationMs = draft.durationMs,
                        isPublic = draft.isPublic,
                        hasSensitiveInfo = validation.hasSensitiveInfo,
                        isHighRisk = validation.isHighRisk,
                        sensitiveFlagCount = validation.sensitiveFlagCount
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, error = "Draft not found") }
            }
        }
    }

    fun onTitleChange(title: String) {
        _uiState.update { it.copy(title = title) }
    }

    fun onEmotionChange(emotion: String) {
        _uiState.update { it.copy(emotion = emotion) }
    }

    fun onConfirmComfortable(confirmed: Boolean) {
        _uiState.update { it.copy(confirmedComfortable = confirmed) }
    }

    fun onConfirmSensitiveRemoved(confirmed: Boolean) {
        _uiState.update { it.copy(confirmedSensitiveRemoved = confirmed) }
    }

    fun onConfirmComplete(confirmed: Boolean) {
        _uiState.update { it.copy(confirmedComplete = confirmed) }
    }

    fun onApproveAndPublish() {
        if (!_uiState.value.canApprove) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            // Psychological Pacing: Intentional "Processing" pause
            // Creating a moment of finality and confirmation
            kotlinx.coroutines.delay(2000)

            val result = repository.approveAndFreeze(draftId, _uiState.value.transcript)
            if (result.isSuccess) {
                _uiState.update { it.copy(isLoading = false, isSuccess = true, currentState = ArtifactDraftState.WAITING_FOR_NETWORK) }
            } else {
                _uiState.update { it.copy(isLoading = false, error = result.exceptionOrNull()?.message ?: "Approval failed") }
            }
        }
    }
}
