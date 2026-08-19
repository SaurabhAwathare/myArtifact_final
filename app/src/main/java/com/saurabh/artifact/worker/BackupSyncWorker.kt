package com.saurabh.artifact.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.storage.FirebaseStorage
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.ProcessingStage
import com.saurabh.artifact.model.ProcessingStatus
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.util.ConnectivityObserver
import com.saurabh.artifact.util.EncryptedStorageManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.tasks.await
import java.io.File

@HiltWorker
class BackupSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: dagger.Lazy<DraftDao>,
    private val authRepository: AuthRepository,
    private val encryptedStorageManager: EncryptedStorageManager,
    private val backupEncryptionManager: BackupEncryptionManager,
    private val storage: FirebaseStorage,
    private val connectivityObserver: ConnectivityObserver,
    private val diagnosticLogger: DiagnosticLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userId = authRepository.currentUser.value?.uid ?: return Result.failure()
        diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "BACKUP_SYNC_STARTED")
        
        if (!connectivityObserver.isOnline()) {
            return Result.retry()
        }

        // 1. Get drafts that are not published, not deleting, and not yet backed up
        // Query is user-scoped at the DAO level for defense-in-depth.
        val pendingDrafts = draftDao.get().getAllDraftsByUserId(userId).filter { 
            (it.lifecycle != ArtifactLifecycle.PUBLISHED) &&
            (it.lifecycle != ArtifactLifecycle.DELETING) &&
            (it.lifecycle != ArtifactLifecycle.DELETED) &&
            (it.status.backup !is SyncStatus.Synced)
        }

        if (pendingDrafts.isEmpty()) return Result.success()

        var successCount = 0
        for (draft in pendingDrafts) {
            try {
                // 2. Update status to Encrypting
                draftDao.get().updateStatus(
                    draft.id, 
                    userId,
                    draft.status.copy(processing = ProcessingStatus.Active(ProcessingStage.ENCRYPTING_BACKUP)),
                )

                val audioFile = File(draft.localAudioPath)
                if (!audioFile.exists()) continue

                // 3. Encrypt for Backup (Decrypt local file first if it's encrypted)
                val audioData = if (draft.isEncrypted) {
                    encryptedStorageManager.getEncryptedInputStream(audioFile).use { it.readBytes() }
                } else {
                    audioFile.readBytes()
                }

                val encryptedData = backupEncryptionManager.encryptForBackup(audioData)

                // 4. Upload to "backups" folder in Firebase Storage
                val backupRef = storage.reference.child("backups/$userId/${draft.id}.enc")
                backupRef.putBytes(encryptedData).await()

                // 5. Update DB status to Synced
                draftDao.get().updateStatus(
                    draft.id, 
                    userId,
                    draft.status.copy(
                        processing = ProcessingStatus.Idle,
                        backup = SyncStatus.Synced,
                    ),
                )
                successCount++
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.WORKMANAGER, "BACKUP_DRAFT_FAILED", mapOf(LogKeys.DRAFT_ID to draft.id), e)
            }
        }

        return if (successCount == pendingDrafts.size) {
            diagnosticLogger.info(DiagnosticCategory.WORKMANAGER, "BACKUP_SYNC_SUCCESS", mapOf("count" to successCount))
            Result.success()
        } else {
            diagnosticLogger.warn(DiagnosticCategory.WORKMANAGER, "BACKUP_SYNC_PARTIAL_FAILURE", mapOf("success" to successCount, "total" to pendingDrafts.size))
            Result.retry()
        }
    }
}
