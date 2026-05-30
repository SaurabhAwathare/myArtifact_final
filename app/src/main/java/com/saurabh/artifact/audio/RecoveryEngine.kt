package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.SyncState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecoveryEngine @Inject constructor(
    private val draftDao: DraftDao,
    private val localDraftManager: LocalDraftManager,
    private val wavRecoveryManager: WavRecoveryManager
) {
    suspend fun runRecovery() {
        Log.d("RecoveryEngine", "Starting recovery check...")
        val recordings = draftDao.getActiveRecordings()
        recordings.forEach { draft ->
            val pcmPath = draft.rawPcmPath ?: draft.localAudioPath
            val file = File(pcmPath)
            
            if (file.exists()) {
                // If it's a recording that hasn't seen a checkpoint in 60s, it's interrupted.
                if (System.currentTimeMillis() - draft.lastCheckpointTs > 60_000) {
                    Log.w("RecoveryEngine", "Found interrupted draft: ${draft.id}")
                    
                    val recoveryResult = wavRecoveryManager.recover(file, lastDurableBytes = draft.durableBytes)
                    
                    val newState = when (recoveryResult) {
                        WavRecoveryManager.RecoveryResult.REPAIRED,
                        WavRecoveryManager.RecoveryResult.FULLY_RECOVERED,
                        WavRecoveryManager.RecoveryResult.TRUNCATED -> SyncState.STAGED
                        WavRecoveryManager.RecoveryResult.CORRUPTED,
                        WavRecoveryManager.RecoveryResult.NOT_FOUND -> SyncState.FAILED_PERMANENT
                    }

                    Log.d("RecoveryEngine", "Recovery for ${draft.id}: $recoveryResult -> New State: $newState")
                    
                    draftDao.updateSyncState(draft.id, newState)
                    if (newState == SyncState.FAILED_PERMANENT) {
                        draftDao.updateDraftState(draft.id, ArtifactDraftState.ERROR)
                    } else {
                        draftDao.updateDraftState(draft.id, ArtifactDraftState.SAVING)
                    }
                    draftDao.updateInterruptionReason(draft.id, "System interruption - recovered via ${recoveryResult.name}.")
                }
            } else {
                Log.e("RecoveryEngine", "Draft file missing for ${draft.id}")
                draftDao.update(draft.copy(syncState = SyncState.FAILED_PERMANENT, isCorrupted = true))
            }
        }
        
        try {
            val allDrafts = draftDao.getAllDrafts()
            val knownPaths = mutableSetOf<String>()
            allDrafts.forEach {
                knownPaths.add(it.localAudioPath)
                it.rawPcmPath?.let { p -> knownPaths.add(p) }
                it.waveformPath?.let { p -> knownPaths.add(p) }
                it.localTranscriptPath?.let { p -> knownPaths.add(p) }
                it.frozenAudioPath?.let { p -> knownPaths.add(p) }
            }
            
            localDraftManager.cleanupOrphans(knownPaths)
            
            val publishedDrafts = draftDao.getDraftsByState(ArtifactDraftState.PUBLISHED)
            publishedDrafts.forEach { draft ->
                Log.d("RecoveryEngine", "Belated cleanup for published draft: ${draft.id}")
                localDraftManager.deleteDraftFiles(draft)
                draftDao.deleteById(draft.id)
            }
        } catch (e: Exception) {
            Log.e("RecoveryEngine", "Cleanup orphans/published failed", e)
        }
    }
}
