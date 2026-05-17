package com.saurabh.artifact.presentation.publish

import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.FlaggedSegment

sealed class PublishRitualState {
    object Idle : PublishRitualState()
    object Processing : PublishRitualState()
    data class Reviewing(val transcriptJson: String) : PublishRitualState()
    data class PrivacyCheck(val findings: List<FlaggedSegment>) : PublishRitualState()
    data class EmotionalSelection(val detected: Emotion?) : PublishRitualState()
    object FinalConfirmation : PublishRitualState()
    object Publishing : PublishRitualState()
    object Success : PublishRitualState()
    data class Error(val message: String) : PublishRitualState()
}
