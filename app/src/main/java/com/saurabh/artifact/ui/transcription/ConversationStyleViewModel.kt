package com.saurabh.artifact.ui.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.ConversationStyle
import com.saurabh.artifact.model.StyleModerationState
import com.saurabh.artifact.nlp.ConversationStyleDetector
import com.saurabh.artifact.service.PersonalizationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationStyleViewModel @Inject constructor(
    private val styleDetector: ConversationStyleDetector,
    private val personalizationEngine: PersonalizationEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationStyleUiState())
    val uiState: StateFlow<ConversationStyleUiState> = _uiState.asStateFlow()

    init {
        // Observe personalization changes to update "Who will hear this" preview
        viewModelScope.launch {
            personalizationEngine.userProfile.collect { profile ->
                // Simple logic: more users hear content that matches the general style affinity
                val estimatedListeners = (10..500).random() // Placeholder for actual ranking logic
                _uiState.update { it.copy(previewListenerCount = estimatedListeners) }
            }
        }
    }

    /**
     * Initializes analysis of the transcript to suggest styles.
     */
    fun analyzeTranscript(transcript: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true) }
            val suggestions = styleDetector.detectStyles(transcript)
            _uiState.update { 
                it.copy(
                    aiSuggestions = suggestions,
                    isAnalyzing = false,
                    // Auto-select top suggestion if confidence is high
                    selectedPrimaryStyle = if (suggestions.firstOrNull()?.confidence ?: 0f > 0.7f) {
                        suggestions.first().style
                    } else it.selectedPrimaryStyle
                )
            }
        }
    }

    fun onPrimaryStyleSelected(style: ConversationStyle) {
        _uiState.update { 
            it.copy(
                selectedPrimaryStyle = if (it.selectedPrimaryStyle == style) null else style,
                // Remove from secondary if it was there
                selectedSecondaryStyles = it.selectedSecondaryStyles - style
            )
        }
        validateSafety()
    }

    fun onSecondaryStyleToggled(style: ConversationStyle) {
        _uiState.update { state ->
            if (state.selectedPrimaryStyle == style) return@update state
            
            val current = state.selectedSecondaryStyles
            val next = if (current.contains(style)) {
                current - style
            } else {
                if (current.size < 2) current + style else current
            }
            state.copy(selectedSecondaryStyles = next)
        }
        validateSafety()
    }

    private fun validateSafety() {
        val state = _uiState.value
        val isVolatile = state.selectedPrimaryStyle == ConversationStyle.RANT && 
                         state.selectedSecondaryStyles.contains(ConversationStyle.CHAOTIC)
        
        _uiState.update { 
            it.copy(moderationState = if (isVolatile) StyleModerationState.SENSITIVE else StyleModerationState.SAFE) 
        }
    }

    fun onSave(onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            // Logic to persist styles to Firestore would go here
            onComplete()
            _uiState.update { it.copy(isSaving = false) }
        }
    }
}
