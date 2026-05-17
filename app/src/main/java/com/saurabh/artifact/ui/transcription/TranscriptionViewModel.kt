package com.saurabh.artifact.ui.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.AudioSemanticEditor
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.TranscriptionRepository
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.util.TranscriptRetimer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TranscriptionViewModel @Inject constructor(
    private val transcriptionRepository: TranscriptionRepository,
    private val recordingRepository: RecordingRepository,
    private val semanticEditor: AudioSemanticEditor
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranscriptionUiState())
    val uiState: StateFlow<TranscriptionUiState> = _uiState.asStateFlow()

    fun loadDraft(draftId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, draftId = draftId) }
            val draft = recordingRepository.getDraft(draftId)
            if (draft != null) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        transcript = emptyList(), // Load from repository in real scenario
                        state = com.saurabh.artifact.model.TranscriptionState.TRANSCRIBING
                    )
                }
                startTranscription(draftId, "mock_url")
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Draft not found") }
            }
        }
    }

    private fun startTranscription(draftId: String, audioUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(state = com.saurabh.artifact.model.TranscriptionState.TRANSCRIBING) }
            val result = transcriptionRepository.requestRemoteTranscription(draftId, audioUrl)
            result.onSuccess { jobId ->
                _uiState.update { it.copy(state = com.saurabh.artifact.model.TranscriptionState.ANALYZING) }
                simulateCompletion()
            }.onFailure { error ->
                _uiState.update { it.copy(state = com.saurabh.artifact.model.TranscriptionState.ERROR, errorMessage = error.message) }
            }
        }
    }

    private fun simulateCompletion() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            val mockTranscript = listOf(
                TranscriptSegment("1", "Hello world", 0, 1000, 0.99f),
                TranscriptSegment("2", "This is an emotional artifact.", 1100, 3000, 0.95f),
                TranscriptSegment("3", "I also argued with my brother...", 3100, 6000, 0.90f)
            )
            _uiState.update { 
                it.copy(
                    state = com.saurabh.artifact.model.TranscriptionState.REVIEWING,
                    transcript = mockTranscript
                )
            }
        }
    }

    fun toggleSegmentSelection(segmentId: String) {
        _uiState.update { state ->
            val selected = state.selectedSegmentIds.toMutableSet()
            if (selected.contains(segmentId)) {
                selected.remove(segmentId)
            } else {
                selected.add(segmentId)
            }
            state.copy(selectedSegmentIds = selected)
        }
    }

    fun applySemanticEdit(action: EditAction) {
        val selectedIds = _uiState.value.selectedSegmentIds
        if (selectedIds.isEmpty()) return

        val segments = _uiState.value.transcript.filter { it.id in selectedIds }
        val label = when (action) {
            EditAction.REMOVE -> "Removed ${segments.size} segments"
            EditAction.MUTE -> "Muted ${segments.size} segments"
            EditAction.REDACT -> "Redacted ${segments.size} segments"
        }

        val operation = SemanticEditOperation(
            segmentIds = selectedIds.toList(),
            action = action,
            label = label
        )

        _uiState.update { state ->
            val history = state.editHistory
            val newOps = history.operations.subList(0, history.currentIndex + 1) + operation
            state.copy(
                editHistory = EditHistory(newOps, newOps.size - 1),
                selectedSegmentIds = emptySet()
            )
        }
        
        processActiveEdits()
    }

    fun undo() {
        _uiState.update { state ->
            if (state.editHistory.canUndo) {
                state.copy(editHistory = state.editHistory.copy(currentIndex = state.editHistory.currentIndex - 1))
            } else state
        }
        processActiveEdits()
    }

    fun redo() {
        _uiState.update { state ->
            if (state.editHistory.canRedo) {
                state.copy(editHistory = state.editHistory.copy(currentIndex = state.editHistory.currentIndex + 1))
            } else state
        }
        processActiveEdits()
    }

    private fun processActiveEdits() {
        viewModelScope.launch {
            val state = _uiState.value
            val activeOps = state.editHistory.activeOperations
            
            // 1. Recalculate UI Transcript (Optimistic Update)
            val originalSegments = state.transcript // Assuming 'transcript' holds originals or we have a source
            // In a real app, we'd keep 'originalTranscript' separate.
            // For now, let's assume we can derive from the current or a cached list.
            
            // 2. Update Playback Map
            val playbackMap = TranscriptRetimer.generatePlaybackMap(originalSegments, activeOps)
            _uiState.update { it.copy(playbackMap = playbackMap) }
            
            // 3. Process Audio (Real Processing)
            // In a production app, we might wait for the user to click "Preview" or "Publish".
            // Here we trigger it if removal is involved.
            if (activeOps.any { it.action == EditAction.REMOVE }) {
                // val audioFile = getAudioFile()
                // semanticEditor.processEdits(audioFile, originalSegments, activeOps).collect { ... }
            }
        }
    }

    fun updateSegmentText(segmentId: String, newText: String) {
        _uiState.update { state ->
            val updatedTranscript = state.transcript.map {
                if (it.id == segmentId) it.copy(text = newText) else it
            }
            state.copy(transcript = updatedTranscript)
        }
    }

    fun togglePlayback() {
        _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun retryTranscription() {
        val draftId = _uiState.value.draftId
        if (draftId.isNotEmpty()) {
            startTranscription(draftId, "mock_url")
        }
    }
}
