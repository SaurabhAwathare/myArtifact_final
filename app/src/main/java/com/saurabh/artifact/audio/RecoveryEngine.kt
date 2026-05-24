package com.saurabh.artifact.audio

import android.util.Log
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.SyncState
import java.io.File
import java.io.RandomAccessFile
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
            val pcmPath = draft.rawPcmPath ?: draft.localAudioPath
            val file = File(pcmPath)
            
            if (file.exists()) {
                if (System.currentTimeMillis() - draft.lastCheckpointTs > 60_000) {
                    // Stale recording, mark as interrupted
                    Log.w("RecoveryEngine", "Found interrupted draft: ${draft.id}")
                    
                    // HEAL: If it's a WAV file with an incomplete header (0s), fix it
                    if (file.extension.lowercase() == "wav") {
                        healWavHeader(file)
                    }
                    
                    draftDao.updateSyncState(draft.id, SyncState.INTERRUPTED)
                    draftDao.updateDraftState(draft.id, ArtifactDraftState.ERROR)
                    draftDao.updateInterruptionReason(draft.id, "System interruption - recovered safely.")
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
                it.rawPcmPath?.let { p -> knownPaths.add(p) }
                it.waveformPath?.let { p -> knownPaths.add(p) }
                it.localTranscriptPath?.let { p -> knownPaths.add(p) }
                it.frozenAudioPath?.let { p -> knownPaths.add(p) }
            }
            localDraftManager.cleanupOrphans(knownPaths)
        } catch (e: Exception) {
            Log.e("RecoveryEngine", "Cleanup orphans failed", e)
        }
    }

    private fun healWavHeader(file: File) {
        try {
            val totalAudioLen = file.length() - 44
            if (totalAudioLen < 0) return
            
            val totalDataLen = totalAudioLen + 36
            val sampleRate = 44100
            val channels = 1
            val byteRate = sampleRate * channels * 16 / 8

            val raf = RandomAccessFile(file, "rw")
            val header = ByteArray(44)

            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = (totalDataLen shr 8 and 0xff).toByte()
            header[6] = (totalDataLen shr 16 and 0xff).toByte()
            header[7] = (totalDataLen shr 24 and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = (sampleRate shr 8 and 0xff).toByte()
            header[26] = (sampleRate shr 16 and 0xff).toByte()
            header[27] = (sampleRate shr 24 and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = (byteRate shr 8 and 0xff).toByte()
            header[30] = (byteRate shr 16 and 0xff).toByte()
            header[31] = (byteRate shr 24 and 0xff).toByte()
            header[32] = (channels * 16 / 8).toByte()
            header[33] = 0
            header[34] = 16
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = (totalAudioLen shr 8 and 0xff).toByte()
            header[42] = (totalAudioLen shr 16 and 0xff).toByte()
            header[43] = (totalAudioLen shr 24 and 0xff).toByte()

            raf.seek(0)
            raf.write(header)
            raf.close()
            Log.d("RecoveryEngine", "Healed WAV header for: ${file.name}")
        } catch (e: Exception) {
            Log.e("RecoveryEngine", "Failed to heal WAV header", e)
        }
    }
}
