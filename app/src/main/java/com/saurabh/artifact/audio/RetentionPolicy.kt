package com.saurabh.artifact.audio

import java.util.concurrent.TimeUnit

/**
 * Centralized constants and policies for artifact retention and cleanup.
 */
object RetentionPolicy {
    /**
     * Default duration to keep local files for a published artifact.
     */
    const val DEFAULT_RETENTION_DAYS = 30L
    
    /**
     * If available storage drops below this threshold, published local files are purged regardless of age.
     */
    const val EMERGENCY_STORAGE_THRESHOLD_MB = 100L
    
    /**
     * Files created within this duration will not be deleted during storage reconciliation.
     */
    const val RECONCILIATION_GRACE_PERIOD_MS = 2 * 60 * 60 * 1000L // 2 hours
    
    /**
     * Standard time unit for retention scheduling.
     */
    val RETENTION_TIME_UNIT = TimeUnit.DAYS
}
