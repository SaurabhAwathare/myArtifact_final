package com.saurabh.artifact.ui.transcription

import com.saurabh.artifact.model.TranscriptSegment

data class TranscriptionUiState(
    val draftId: String = "",
    val state: com.saurabh.artifact.model.TranscriptionState = com.saurabh.artifact.model.TranscriptionState.IDLE,
    val transcript: List<TranscriptSegment> = emptyList(),
    val currentAudioPositionMs: Long = 0,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val sensitiveSegments: List<String> = emptyList(), // IDs of segments flagged by PII scan
    val showReflectionPrompt: Boolean = false,
    val reflectionPrompt: String = "What inspired you to share this story?",
    val editHistory: com.saurabh.artifact.model.EditHistory = com.saurabh.artifact.model.EditHistory(),
    val isProcessing: Boolean = false,
    val processingMessage: String = "",
    val selectedSegmentIds: Set<String> = emptySet(),
    val playbackMap: com.saurabh.artifact.model.PlaybackMap? = null
)
