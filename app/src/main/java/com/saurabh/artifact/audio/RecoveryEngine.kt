package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.SyncState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecoveryEngine @Inject constructor(
    private val draftDao: DraftDao,
    private val localDraftManager: LocalDraftManager
) {
    suspend fun runRecovery() {
        Log.d("RecoveryEngine", "Starting recovery check...")
        val recordings = draftDao.getActiveRecordings()
        recordings.forEach { draft ->
            val file = File(draft.localAudioPath)
            if (file.exists()) {
                if (System.currentTimeMillis() - draft.lastCheckpointTs > 60_000) {
                    // Stale recording, mark as interrupted
                    Log.w("RecoveryEngine", "Found interrupted draft: ${draft.id}")
                    draftDao.updateSyncState(draft.id, SyncState.INTERRUPTED)
                }
            } else {
                // File missing, mark as corrupted
                Log.e("RecoveryEngine", "Draft file missing for ${draft.id}")
                draftDao.update(draft.copy(syncState = SyncState.FAILED_PERMANENT, isCorrupted = true))
            }
        }
        
        // Clean up orphaned files to free storage
        try {
            val allDrafts = draftDao.getAllDrafts()
            val knownPaths = mutableSetOf<String>()
            allDrafts.forEach {
                knownPaths.add(it.localAudioPath)
                it.waveformPath?.let { p -> knownPaths.add(p) }
                it.localTranscriptPath?.let { p -> knownPaths.add(p) }
                it.frozenAudioPath?.let { p -> knownPaths.add(p) }
            }
            localDraftManager.cleanupOrphans(knownPaths)
        } catch (e: Exception) {
            Log.e("RecoveryEngine", "Cleanup orphans failed", e)
        }
    }
}
