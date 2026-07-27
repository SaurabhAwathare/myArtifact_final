package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftDeletionManager @Inject constructor(
    private val storageManager: StorageManager
) {

    /**
     * Performs pure physical local resource cleanup.
     * Does NOT modify database state or schedule workers.
     * Intended to be called by CleanupWorker as part of the state-driven pipeline.
     *
     * NOTE: Legacy deleteDraft() is removed to ensure all deletions flow through 
     * the ArtifactCleanupManager -> CleanupWorker pipeline.
     */
    suspend fun performPhysicalPurge(draft: ArtifactDraftEntity) = withContext(Dispatchers.IO) {
        Log.d("DraftDeletionManager", "Purging physical assets for draft: ${draft.id}")
        val draftDir = storageManager.getDraftDirectory(draft.id)
        storageManager.deleteDirectoryRecursively(draftDir)
        
        val legacyFiles = listOfNotNull(
            draft.localAudioPath,
            draft.rawPcmPath,
            draft.waveformPath,
            draft.frozenAudioPath
        ).map { File(it) }
        
        legacyFiles.forEach { file ->
            if (file.exists() && !file.absolutePath.startsWith(draftDir.absolutePath)) {
                storageManager.deleteSecurely(file)
            }
        }
    }
}
