package com.saurabh.artifact.data.local

import android.util.Log
import com.saurabh.artifact.audio.RetentionPolicy
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseMaintenanceManager @Inject constructor(
    private val database: dagger.Lazy<AppDatabase>,
    private val engagementDao: dagger.Lazy<EngagementDao>,
    private val deadLetterDao: Lazy<DeadLetterInteractionDao>,
    private val draftDao: dagger.Lazy<DraftDao>,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository
) {
    companion object {
        private const val TAG = "DatabaseMaintenance"
    }

    /**
     * Executes a full maintenance cycle: pruning old data and compacting the database.
     */
    suspend fun runMaintenance() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting database maintenance cycle...")
        try {
            pruneOldData()
            compact()
            Log.i(TAG, "Database maintenance cycle completed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Database maintenance cycle failed", e)
        }
    }

    /**
     * Prunes old records from tables based on defined retention policies.
     */
    private suspend fun pruneOldData() {
        val now = System.currentTimeMillis()

        // Prune Displayed Notification deduplication records (7 days retention, device-scoped)
        // Room deduplication retention (7 days) intentionally exceeds maximum FCM Android TTL (6 days)
        // so that any FCM message stored offline by FCM servers expires (max 6 days) before its deduplication
        // entry is purged from the local Room database (7 days). This guarantees that redelivered or delayed
        // offline FCM messages are safely suppressed upon device reconnection.
        val notificationDeduplicationThreshold = now - (7 * 24 * 60 * 60 * 1000L)
        val deletedCount = database.get().displayedNotificationDao().deleteExpired(notificationDeduplicationThreshold)
        Log.d(TAG, "Pruned $deletedCount displayed notification deduplication records older than 7 days")

        val userId = authRepository.currentUserId
        if (userId.isEmpty()) {
            Log.w(TAG, "Skipping user-scoped data pruning: User unauthenticated")
            return
        }

        // 1. Prune Engagement data (60 days, UID-scoped)
        val engagementThreshold = now - (RetentionPolicy.ENGAGEMENT_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        engagementDao.get().deleteOldEngagements(engagementThreshold, userId)
        Log.d(TAG, "Pruned engagement data older than ${RetentionPolicy.ENGAGEMENT_RETENTION_DAYS} days for user")

        // 2. Prune Terminal Permanent DLQ Failures (30 days, UID-scoped)
        // Recoverable RETRY_LIMIT_EXCEEDED records are retained until recovery.
        // Unsynchronized pending interactions are NEVER age-deleted.
        val dlqThreshold = now - (RetentionPolicy.DEFAULT_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        deadLetterDao.get().deleteOldPermanentDeadLetters(dlqThreshold, userId)
        Log.d(TAG, "Pruned terminal permanent DLQ failures older than ${RetentionPolicy.DEFAULT_RETENTION_DAYS} days for user")

        // 3. Prune Published Drafts Metadata (30 days, UID-scoped)
        val draftThreshold = now - (RetentionPolicy.DRAFT_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        draftDao.get().deleteOldPublishedDrafts(draftThreshold, userId)
        Log.d(TAG, "Pruned published draft metadata older than ${RetentionPolicy.DRAFT_RETENTION_DAYS} days for user")
    }

    /**
     * Executes VACUUM to reclaim disk space from deleted rows.
     */
    private fun compact() {
        Log.i(TAG, "Compacting database (VACUUM)...")
        database.get().openHelper.writableDatabase.execSQL("VACUUM")
    }
}
