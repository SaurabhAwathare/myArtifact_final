package com.saurabh.artifact.domain.auth

/**
 * Status of the cleanup operation.
 */
enum class CleanupStatus {
    COMPLETED,
    ALREADY_IN_PROGRESS
}

/**
 * Detailed outcome of a full session cleanup.
 * Used for logging, debugging, and verification.
 */
data class CleanupResult(
    val status: CleanupStatus = CleanupStatus.COMPLETED,
    val recording: Boolean = true,
    val playback: Boolean = true,
    val uploads: Boolean = true,
    val workers: Boolean = true,
    val notifications: Boolean = true,
    val room: Boolean = true,
    val sessionDataStore: Boolean = true,
    val settingsDataStore: Boolean = true,
    val mediaCache: Boolean = true,
) {
    val isFullySuccessful: Boolean get() = listOf(
        recording, playback, uploads, workers, notifications,
        room, sessionDataStore, settingsDataStore, mediaCache
    ).all { it }

    override fun toString(): String {
        return """
            CleanupResult:
            - Status: $status
            - Recording: $recording
            - Playback: $playback
            - Uploads: $uploads
            - Workers: $workers
            - Notifications: $notifications
            - Room: $room
            - Session DS: $sessionDataStore
            - Settings DS: $settingsDataStore
            - Media Cache: $mediaCache
            Summary: ${if (isFullySuccessful) "SUCCESS" else "PARTIAL FAILURE"}
        """.trimIndent()
    }
}
