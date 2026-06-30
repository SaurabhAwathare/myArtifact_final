package com.saurabh.artifact.data.local

import android.util.Log
import com.saurabh.artifact.audio.RetentionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseMaintenanceManager @Inject constructor(
    private val database: dagger.Lazy<AppDatabase>,
    private val engagementDao: dagger.Lazy<EngagementDao>,
    private val interactionDao: dagger.Lazy<PendingInteractionDao>,
    private val draftDao: dagger.Lazy<DraftDao>,
    private val uploadDao: dagger.Lazy<QueuedUploadDao>
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

        // 1. Prune Engagement data
        val engagementThreshold = now - (RetentionPolicy.ENGAGEMENT_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        engagementDao.get().deleteOldEngagements(engagementThreshold)
        Log.d(TAG, "Pruned engagement data older than ${RetentionPolicy.ENGAGEMENT_RETENTION_DAYS} days")

        // 2. Prune Pending Interactions
        val interactionThreshold = now - (RetentionPolicy.INTERACTION_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        interactionDao.get().deleteOldInteractions(interactionThreshold)
        Log.d(TAG, "Pruned interaction data older than ${RetentionPolicy.INTERACTION_RETENTION_DAYS} days")

        // 3. Prune Published Drafts (Metadata)
        val draftThreshold = now - (RetentionPolicy.DRAFT_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        draftDao.get().deleteOldPublishedDrafts(draftThreshold)
        Log.d(TAG, "Pruned published draft metadata older than ${RetentionPolicy.DRAFT_RETENTION_DAYS} days")

        // 4. Prune Stale Queued Uploads
        val uploadThreshold = now - (7 * 24 * 60 * 60 * 1000L) // Hardcoded 1 week for stale uploads
        uploadDao.get().deleteOldQueuedUploads(uploadThreshold)
        Log.d(TAG, "Pruned stale queued uploads older than 7 days")
    }

    /**
     * Executes VACUUM to reclaim disk space from deleted rows.
     */
    private fun compact() {
        Log.i(TAG, "Compacting database (VACUUM)...")
        database.get().openHelper.writableDatabase.execSQL("VACUUM")
    }
}
