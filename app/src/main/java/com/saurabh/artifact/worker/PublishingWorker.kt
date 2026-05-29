package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.saurabh.artifact.R
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@HiltWorker
class PublishingWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao,
    private val artifactRepository: ArtifactRepository,
    private val userRepository: com.saurabh.artifact.repository.UserRepository,
    private val cleanupManager: com.saurabh.artifact.audio.ArtifactCleanupManager
) : CoroutineWorker(appContext, workerParams) {

    private var startTime = System.currentTimeMillis()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure()
        
        startTime = System.currentTimeMillis()

        // 0. Initialize Foreground Service for long-running upload
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w("PublishingWorker", "Could not set foreground info", e)
        }

        // 1. Check if already published to prevent duplicates
        if (draft.draftState == ArtifactDraftState.PUBLISHED) {
            return@withContext Result.success()
        }

        // 2. Use Frozen Snapshot if available
        val audioPath = draft.frozenAudioPath ?: draft.localAudioPath
        
        // 3. Authentication check
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            draftDao.updateDraftState(draftId, ArtifactDraftState.ERROR)
            return@withContext Result.failure()
        }

        try {
            // 4. Update state to UPLOADING
            draftDao.updateDraftState(draftId, ArtifactDraftState.UPLOADING)

            // 5. Check if Audio is already uploaded (Checkpoint)
            val downloadUrl = if (draft.uploadedAudioUrl != null) {
                Log.i("PublishingWorker", "Using checkpointed audio URL for $draftId")
                draft.uploadedAudioUrl
            } else {
                // Upload Audio (Resumable)
                val uploadResult = artifactRepository.uploadArtifactResumable(
                    userId = user.uid,
                    draft = draft.copy(localAudioPath = audioPath), // Use frozen path
                    onProgress = { transferred, total, sessionUri ->
                        draftDao.updateSyncProgress(draftId, transferred, total, sessionUri?.toString())
                        updateNotificationIfNeeded(draft.title ?: "Artifact", transferred, total)
                    }
                )

                val url = uploadResult.getOrThrow()
                
                // Persist Checkpoint
                draftDao.updateUploadCheckpoint(draftId, url, ArtifactDraftState.AUDIO_UPLOADED)
                url
            }

            // 6. Fetch Anonymous Identity from Firestore
            val userProfile = userRepository.getOrCreateProfile()

            // 7. Create Firestore Document (using anonymous identity snapshot)
            val firestoreResult = artifactRepository.createArtifactDocument(
                userId = user.uid,
                username = userProfile.anonymousName,
                audioUrl = downloadUrl,
                draft = draft,
                avatarSeed = userProfile.avatarSeed,
                avatarColor = userProfile.avatarColor,
                avatarConfig = userProfile.avatarConfig,
                anonymousId = userProfile.anonymousId
            )

            val remoteId = firestoreResult.getOrThrow()

            // 7. Success - Atomically update state
            draftDao.markAsPublished(draftId, remoteId)

            // 8. Schedule Automatic Cleanup (30 days)
            cleanupManager.scheduleRetentionCleanup(remoteId)

            NotificationHelper.showUploadSuccessNotification(appContext, draft.title ?: "Artifact")
            Log.d("PublishingWorker", "Draft $draftId published successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("PublishingWorker", "Publishing failed for $draftId", e)
            
            // Check if it's a transient failure or permanent
            val isPermanent = e is com.google.firebase.storage.StorageException && 
                (e.errorCode == com.google.firebase.storage.StorageException.ERROR_NOT_AUTHORIZED || 
                 e.errorCode == com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND)

            if (isPermanent) {
                draftDao.updateDraftState(draftId, ArtifactDraftState.ERROR)
                NotificationHelper.showUploadErrorNotification(appContext, draft.title ?: "Artifact")
                Result.failure()
            } else {
                draftDao.updateDraftState(draftId, ArtifactDraftState.FAILED_UPLOAD)
                Result.retry()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return NotificationHelper.getUploadForegroundInfo(
            appContext,
            "Artifact",
            0
        )
    }

    private fun updateNotificationIfNeeded(title: String, transferred: Long, total: Long) {
        val now = System.currentTimeMillis()
        val duration = now - startTime
        
        // Only show notification if backgrounded (simplified check) or taking long
        if (duration > 5000) {
            val progress = (transferred * 100 / total).toInt()
            NotificationHelper.updateUploadProgress(appContext, title, progress)
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
