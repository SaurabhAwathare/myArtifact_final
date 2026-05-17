package com.saurabh.artifact.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.repository.ArtifactRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class PublishingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val draftDao: DraftDao,
    private val artifactRepository: ArtifactRepository,
    private val userRepository: com.saurabh.artifact.repository.UserRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return@withContext Result.failure()
        val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure()

        // 1. Check if already published to prevent duplicates
        if (draft.draftState == ArtifactDraftState.PUBLISHED) {
            return@withContext Result.success()
        }

        // 2. Use Frozen Snapshot if available
        val audioPath = draft.frozenAudioPath ?: draft.localAudioPath
        val transcriptJson = draft.frozenTranscriptJson
        
        // 3. Authentication check
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            draftDao.updateDraftState(draftId, ArtifactDraftState.ERROR)
            return@withContext Result.failure()
        }

        try {
            // 4. Update state to UPLOADING
            draftDao.updateDraftState(draftId, ArtifactDraftState.UPLOADING)

            // 5. Upload Audio (Resumable)
            val uploadResult = artifactRepository.uploadArtifactResumable(
                userId = user.uid,
                draft = draft.copy(localAudioPath = audioPath), // Use frozen path
                onProgress = { transferred, total, sessionUri ->
                    draftDao.updateSyncProgress(draftId, transferred, total, sessionUri?.toString())
                }
            )

            val downloadUrl = uploadResult.getOrThrow()

            // 6. Fetch Anonymous Identity from Firestore
            val userProfile = userRepository.getOrCreateProfile()

            // 7. Create Firestore Document (using anonymous identity)
            val firestoreResult = artifactRepository.createArtifactDocument(
                userId = user.uid,
                username = userProfile.anonymousName,
                avatarColor = userProfile.avatarColor,
                audioUrl = downloadUrl,
                draft = draft,
                userEmoji = userProfile.identityEmoji
            )

            firestoreResult.getOrThrow()

            // 7. Success - Atomically update state
            draftDao.markAsPublished(draftId, downloadUrl) 

            Log.d("PublishingWorker", "Draft $draftId published successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("PublishingWorker", "Publishing failed for $draftId", e)
            draftDao.updateDraftState(draftId, ArtifactDraftState.ERROR)
            Result.retry()
        }
    }

    companion object {
        const val KEY_DRAFT_ID = "key_draft_id"
    }
}
