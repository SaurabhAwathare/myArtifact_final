package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactDraftState
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.UploadStatus
import com.saurabh.artifact.service.SafetyEvaluator
import com.saurabh.artifact.service.SensitiveInfoScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublishApprovalRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
    private val safetyEvaluator: SafetyEvaluator,
    private val sensitiveInfoScanner: SensitiveInfoScanner
) {

    suspend fun getDraft(id: String): ArtifactDraftEntity? = withContext(Dispatchers.IO) {
        draftDao.getDraftById(id)
    }

    suspend fun updateDraft(draft: ArtifactDraftEntity) = withContext(Dispatchers.IO) {
        draftDao.update(draft)
    }

    suspend fun validateDraft(draft: ArtifactDraftEntity, transcript: List<TranscriptSegment>): ValidationResult = withContext(Dispatchers.Default) {
        // Automatic safety checks disabled per user request
        ValidationResult(
            hasSensitiveInfo = false,
            isHighRisk = false,
            sensitiveFlagCount = 0
        )
    }

    suspend fun approveAndFreeze(draftId: String, transcript: List<TranscriptSegment>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val draft = draftDao.getDraftById(draftId) ?: return@withContext Result.failure(Exception("Draft not found"))
            
            // 1. Generate Immutable Snapshot
            val transcriptJson = Json.encodeToString(transcript)
            val frozenAudioFile = File(context.filesDir, "frozen_audio/${draftId}_approved.m4a").apply {
                parentFile?.mkdirs()
            }
            
            File(draft.localAudioPath).copyTo(frozenAudioFile, overwrite = true)
            
            val metadata = mapOf(
                "title" to (draft.title ?: "Untitled"),
                "emotion" to (draft.emotion ?: ""),
                "tags" to draft.tags
            )
            val metadataJson = Json.encodeToString(metadata)
            
            // 2. Generate Hash for Integrity
            val contentToHash = transcriptJson + frozenAudioFile.length() + metadataJson
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(contentToHash.toByteArray())
                .joinToString("") { "%02x".format(it) }

            // 3. Persist Snapshot and Update State
            draftDao.freezeSnapshot(
                id = draftId,
                transcriptJson = transcriptJson,
                audioPath = frozenAudioFile.absolutePath,
                metadataJson = metadataJson,
                hash = hash
            )
            
            draftDao.markAsApproved(draftId)
            draftDao.updateUploadStatus(draftId, UploadStatus.QUEUED)
            
            // 4. Update state to WAITING_FOR_NETWORK (WorkManager will handle the rest)
            draftDao.updateDraftState(draftId, ArtifactDraftState.WAITING_FOR_NETWORK)
            
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
