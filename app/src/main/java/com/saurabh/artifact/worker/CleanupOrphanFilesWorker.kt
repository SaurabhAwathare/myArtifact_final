package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.util.StorageManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Reconciles the filesystem with the Room database to remove orphaned media files.
 * Runs on app startup and periodically to ensure storage hygiene.
 */
@HiltWorker
class CleanupOrphanFilesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: DraftDao,
    private val storageManager: StorageManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("CleanupOrphanFilesWorker", "Starting orphan cleanup...")
        
        return try {
            val rootDir = storageManager.getDraftsRootDirectory()
            if (!rootDir.exists() || !rootDir.isDirectory) return Result.success()

            // 1. Load all valid draft IDs from DB
            val validDraftIds = draftDao.getAllDraftIds().toSet()
            
            // 2. Scan drafts directory
            val draftDirs = rootDir.listFiles() ?: emptyArray()
            var deletedCount = 0
            
            for (dir in draftDirs) {
                if (!dir.isDirectory) continue
                
                // Expecting dir name "draft_<id>"
                val folderName = dir.name
                if (folderName.startsWith("draft_")) {
                    val draftId = folderName.substringAfter("draft_")
                    
                    if (draftId !in validDraftIds) {
                        Log.i("CleanupOrphanFilesWorker", "Deleting orphaned directory: $folderName")
                        if (storageManager.deleteDirectoryRecursively(dir)) {
                            deletedCount++
                        }
                    }
                }
            }
            
            Log.d("CleanupOrphanFilesWorker", "Orphan cleanup complete. Removed $deletedCount orphaned directories.")
            Result.success()
        } catch (e: Exception) {
            Log.e("CleanupOrphanFilesWorker", "Error during orphan cleanup", e)
            Result.failure()
        }
    }
}
