package com.saurabh.artifact.util

/**
 * Centralized source of truth for WorkManager unique work names.
 * Prevents string literal drift between enqueuing and cancellation/observation logic.
 */
object WorkNames {

    /**
     * Name for the artifact publishing fallback worker.
     */
    fun forPublishing(draftId: String): String = "publish_$draftId"

    /**
     * Name for the artifact processing chain (transcoding, normalization, etc.).
     */
    fun forProcessing(draftId: String): String = "process_$draftId"
    
    /**
     * Tag for the retention cleanup worker.
     */
    fun forRetentionCleanup(artifactId: String): String = "retention_cleanup_$artifactId"
    
    /**
     * Tag for the immediate local cleanup worker.
     */
    fun forCleanup(artifactId: String): String = "cleanup_$artifactId"
}
