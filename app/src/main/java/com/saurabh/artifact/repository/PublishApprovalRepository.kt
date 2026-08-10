package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.security.UploadGuard
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublishApprovalRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val draftDao: Lazy<DraftDao>,
    private val uploadGuard: UploadGuard,
    private val authRepository: AuthRepository 
) {

    suspend fun getDraft(id: String): ArtifactDraftEntity? = withContext(Dispatchers.IO) {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return@withContext null
        draftDao.get().getDraftById(id, userId)
    }

    suspend fun updateDraft(draft: ArtifactDraftEntity) = withContext(Dispatchers.IO) {
        // userId check is built into draftDao.update
        draftDao.get().update(draft)
    }

    suspend fun approveAndFreezeAuto(draftId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authRepository.currentUserId
            if (userId.isEmpty()) return@withContext Result.failure(AppError.Unauthenticated())

            val draft = draftDao.get().getDraftById(draftId, userId) 
                ?: return@withContext Result.failure(AppError.NotFound("Draft", draftId))

            Log.d("PublishApprovalRepo", "Starting auto-approval for draft.")

            // 1. Load Transcript from frozen JSON if available
            val transcript = if (draft.frozenTranscriptJson != null) {
                val json = draft.frozenTranscriptJson.toUnsecureString()
                Json.decodeFromString<List<TranscriptSegment>>(json)
            } else {
                emptyList()
            }

            approveAndFreeze(draftId, transcript)
        } catch (e: Exception) {
            Log.e("PublishApprovalRepo", "Auto-approval failed.", e)
            Result.failure(e)
        }
    }

    suspend fun validateDraft(draft: ArtifactDraftEntity, transcript: List<TranscriptSegment>): ValidationResult = withContext(Dispatchers.Default) {
        ValidationResult(
            hasSensitiveInfo = false,
            isHighRisk = false,
            sensitiveFlagCount = 0
        )
    }

    suspend fun approveAndFreeze(draftId: String, transcript: List<TranscriptSegment>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authRepository.currentUserId
            if (userId.isEmpty()) return@withContext Result.failure(AppError.Unauthenticated())

            val draft = draftDao.get().getDraftById(draftId, userId) 
                ?: return@withContext Result.failure(AppError.NotFound("Draft", draftId))
            
            // 1. Generate Immutable Snapshot
            val transcriptJson = if (transcript.isEmpty()) null else Json.encodeToString(transcript)
            val frozenAudioFile = File(context.filesDir, "frozen_audio/${draftId}_approved.m4a").apply {
                parentFile?.mkdirs()
            }
            
            File(draft.localAudioPath).copyTo(frozenAudioFile, overwrite = true)
            
            // 2. Generate Approval Token
            val currentChecksum = MessageDigest.getInstance("SHA-256")
                .digest(frozenAudioFile.readBytes())
                .joinToString("") { "%02x".format(it) }

            val timestamp = System.currentTimeMillis()
            val fingerprint = uploadGuard.getDeviceFingerprint()
            val token = uploadGuard.generateApprovalToken(
                userId = userId,
                draftId = draftId,
                checksum = currentChecksum,
                timestamp = timestamp
            )

            Log.d("PublishApprovalRepo", "Approval token generation success.")

            // 3. Persist Snapshot
            val secureTranscript = transcriptJson?.let { com.saurabh.artifact.util.SecureString.fromString(it) }
            draftDao.get().freezeSnapshot(
                id = draftId,
                userId = userId,
                transcriptJson = secureTranscript,
                audioPath = frozenAudioFile.absolutePath,
                token = token,
                fingerprint = fingerprint,
                timestamp = timestamp
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("PublishApprovalRepo", "Failed to approve and freeze draft", e)
            Result.failure(e)
        }
    }

    data class ValidationResult(
        val hasSensitiveInfo: Boolean,
        val isHighRisk: Boolean,
        val sensitiveFlagCount: Int
    )
}
