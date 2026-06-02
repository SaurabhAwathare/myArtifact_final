package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.storage.FirebaseStorage
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.ProcessingStage
import com.saurabh.artifact.model.ProcessingStatus
import com.saurabh.artifact.model.SyncStatus
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.util.ConnectivityObserver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.tasks.await
import java.io.File

@HiltWorker
class BackupSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val draftDao: DraftDao,
    private val authRepository: AuthRepository,
    private val backupEncryptionManager: BackupEncryptionManager,
    private val storage: FirebaseStorage,
    private val connectivityObserver: ConnectivityObserver
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userId = authRepository.currentUser.value?.uid ?: return Result.failure()
        
        if (!connectivityObserver.isOnline()) {
            return Result.retry()
        }

        // 1. Get drafts that are not published and not yet backed up
        val pendingDrafts = draftDao.getAllDrafts().filter { 
            it.status.lifecycle != ArtifactLifecycle.PUBLISHED && 
            it.status.backup !is SyncStatus.Synced 
        }

        if (pendingDrafts.isEmpty()) return Result.success()

        var successCount = 0
        for (draft in pendingDrafts) {
            try {
                // 2. Update status to Encrypting
                draftDao.updateStatus(draft.id, draft.status.copy(
                    processing = ProcessingStatus.Active(ProcessingStage.ENCRYPTING_BACKUP)
                ))

                val audioFile = File(draft.localAudioPath)
                if (!audioFile.exists()) continue

                // 3. Encrypt for Backup
                val audioData = audioFile.readBytes()
                val encryptedData = backupEncryptionManager.encryptForBackup(audioData)

                // 4. Upload to "backups" folder in Firebase Storage
                val backupRef = storage.reference.child("backups/$userId/${draft.id}.enc")
                backupRef.putBytes(encryptedData).await()

                // 5. Update DB status to Synced
                draftDao.updateStatus(draft.id, draft.status.copy(
                    processing = ProcessingStatus.Idle,
                    backup = SyncStatus.Synced
                ))
                successCount++
            } catch (e: Exception) {
                Log.e("BackupSyncWorker", "Failed to backup draft ${draft.id}", e)
            }
        }

        return if (successCount == pendingDrafts.size) Result.success() else Result.retry()
    }
}
