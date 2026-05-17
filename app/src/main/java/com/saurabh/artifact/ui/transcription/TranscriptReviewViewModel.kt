package com.saurabh.artifact.ui.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.*
import com.saurabh.artifact.service.SensitiveInfoScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranscriptReviewViewModel @Inject constructor(
    private val draftDao: DraftDao,
    private val sensitiveInfoScanner: SensitiveInfoScanner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranscriptionUiState())
    val uiState: StateFlow<TranscriptionUiState> = _uiState.asStateFlow()

    fun loadDraft(draftId: String) {
        viewModelScope.launch {
            val draft = draftDao.getDraftById(draftId) ?: return@launch
            
            // In a real app, segments would be fetched from a file or related table
            // Mocking segments for demonstration
            val mockSegments = listOf(
                TranscriptSegment(id = "1", text = "Today I argued with Rahul at work in Nagpur", startMs = 0, endMs = 5000, confidence = 0.95f),
                TranscriptSegment(id = "2", text = "It was about the new project timeline.", startMs = 5100, endMs = 8000, confidence = 0.98f)
            )

            val flagged = sensitiveInfoScanner.scan(mockSegments)

            _uiState.update { 
                it.copy(
                    draftId = draftId,
                    transcript = mockSegments,
                    sensitiveSegments = flagged.map { f -> f.id }, // Using flagged segment IDs
                    isLoading = false
                )
            }
        }
    }

    fun updateSegmentText(segmentId: String, newText: String) {
        _uiState.update { currentState ->
            val updatedTranscript = currentState.transcript.map { 
                if (it.id == segmentId) it.copy(text = newText) else it
            }
            // Re-scan for sensitivity after edit
            val newFlagged = sensitiveInfoScanner.scan(updatedTranscript)
            
            currentState.copy(
                transcript = updatedTranscript,
                sensitiveSegments = newFlagged.map { it.id }
            )
        }
    }

    fun togglePlayback() {
        _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun onPublishClick() {
        _uiState.update { it.copy(showReflectionPrompt = true) }
    }

    fun dismissReflectionPrompt() {
        _uiState.update { it.copy(showReflectionPrompt = false) }
    }

    fun confirmFinalPublish() {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, processingMessage = "Publishing reflection...") }
            // Logic to call repository and publish
            // repository.publishArtifact(...)
            _uiState.update { it.copy(isProcessing = false, state = TranscriptionState.COMPLETED) }
        }
    }
}
