package com.saurabh.artifact.ui.transcription

import com.saurabh.artifact.model.ConversationStyle
import com.saurabh.artifact.model.StyleSuggestion
import com.saurabh.artifact.model.StyleModerationState

/**
 * UI State for the Conversation Style selection screen.
 */
data class ConversationStyleUiState(
    val selectedPrimaryStyle: ConversationStyle? = null,
    val selectedSecondaryStyles: Set<ConversationStyle> = emptySet(),
    val aiSuggestions: List<StyleSuggestion> = emptyList(),
    val isAnalyzing: Boolean = false,
    val moderationState: StyleModerationState = StyleModerationState.SAFE,
    val previewListenerCount: Int = 0, // Estimated "Who will hear this" preview
    val errorMessage: String? = null,
    val isSaving: Boolean = false
)
