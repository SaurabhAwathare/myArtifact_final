package com.saurabh.artifact.util

/**
 * Centralized feature control for the Artifact application.
 * These flags allow for compile-time toggling of major product features.
 */
object FeatureFlags {
    /**
     * Controls the availability of the transcript review step in the publishing flow.
     * Set to false to bypass the Review screen and associated playback initialization.
     */
    const val REVIEW_ENABLED = true
}
