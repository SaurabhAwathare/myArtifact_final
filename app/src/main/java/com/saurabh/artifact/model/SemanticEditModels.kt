package com.saurabh.artifact.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class EditAction {
    REMOVE, // Completely cut from the audio
    MUTE,   // Silence the audio but keep the timing
    REDACT  // Replace with a beep or silence for privacy
}

@Serializable
data class SemanticEditOperation(
    val id: String = UUID.randomUUID().toString(),
    val segmentIds: List<String>,
    val action: EditAction,
    val timestamp: Long = System.currentTimeMillis(),
    val label: String // e.g., "Removed 'I also argued...'"
)

@Serializable
data class EditHistory(
    val operations: List<SemanticEditOperation> = emptyList(),
    val currentIndex: Int = -1 // For undo/redo support
) {
    val canUndo: Boolean get() = currentIndex >= 0
    val canRedo: Boolean get() = currentIndex < operations.size - 1
    
    val activeOperations: List<SemanticEditOperation> 
        get() = if (currentIndex == -1) emptyList() else operations.subList(0, currentIndex + 1)
}

/**
 * Maps virtual (edited) time back to original audio time for playback syncing.
 */
data class PlaybackMap(
    val segments: List<PlaybackSegmentMapping>
)

data class PlaybackSegmentMapping(
    val virtualStartMs: Long,
    val virtualEndMs: Long,
    val originalStartMs: Long,
    val originalEndMs: Long,
    val isMuted: Boolean = false
)

@Serializable
sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Processing(val progress: Float, val message: String) : ProcessingState()
    data class Completed(val outputFilePath: String) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}
