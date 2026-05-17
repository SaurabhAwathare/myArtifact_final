package com.saurabh.artifact.ui.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.*
import com.saurabh.artifact.nlp.TopicSuggestionEngine
import com.saurabh.artifact.repository.TopicRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TopicTaggingUiState(
    val suggestions: List<TopicSuggestion> = emptyList(),
    val selectedTopics: Set<TopicTag> = emptySet(),
    val searchResults: List<TopicTag> = emptyList(),
    val searchQuery: String = "",
    val isAnalyzing: Boolean = false,
    val isPublishing: Boolean = false,
    val moderationWarning: String? = null
)

class TopicTaggingViewModel(
    private val suggestionEngine: TopicSuggestionEngine,
    private val repository: TopicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TopicTaggingUiState())
    val uiState: StateFlow<TopicTaggingUiState> = _uiState.asStateFlow()

    private val _searchQueryText = MutableStateFlow("")

    init {
        // Debounced search
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            _searchQueryText
                .debounce(300)
                .filter { it.isNotBlank() }
                .collect { query ->
                    val results = repository.searchTopics(query)
                    _uiState.update { it.copy(searchResults = results) }
                }
        }
    }

    /**
     * Initializes the tagging flow with transcript and emotion.
     */
    fun analyzeTranscript(transcript: List<TranscriptSegment>, emotion: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true) }
            val suggestions = suggestionEngine.suggestTopics(transcript, emotion)
            _uiState.update { it.copy(suggestions = suggestions, isAnalyzing = false) }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQueryText.value = query
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
        }
    }

    fun toggleTopic(topic: TopicTag) {
        _uiState.update { state ->
            val newSelected = if (state.selectedTopics.any { it.label == topic.label }) {
                state.selectedTopics.filterNot { it.label == topic.label }.toSet()
            } else {
                if (state.selectedTopics.size >= 5) return@update state // Limit
                state.selectedTopics + topic
            }
            state.copy(selectedTopics = newSelected)
        }
    }

    fun addCustomTopic(label: String) {
        if (label.isBlank()) return
        
        // Simple PII check
        if (label.contains(Regex("\\d{10}"))) {
            _uiState.update { it.copy(moderationWarning = "Phone numbers are not allowed as topics for your safety.") }
            return
        }

        val newTopic = TopicTag(label = label.lowercase().trim(), isSystemGenerated = false)
        toggleTopic(newTopic)
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), moderationWarning = null) }
    }

    fun publish(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true) }
            // Save metadata to artifact...
            onSuccess()
            _uiState.update { it.copy(isPublishing = false) }
        }
    }
}
