package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

/**
 * Authoritative lifecycle for local device cleanup of artifacts.
 * Owned exclusively by the Android application.
 */
@Serializable
enum class LocalCleanupStatus {
    /** Deletion request received and persisted in Room. */
    PENDING,

    /** Purge of files and cache is actively in progress. */
    CLEANING,

    /** All local traces successfully removed. */
    COMPLETED,

    /** A transient error (e.g., IO, storage busy) occurred. */
    FAILED_RETRYABLE,

    /** A permanent error occurred (e.g., filesystem corruption). */
    FAILED_TERMINAL
}
