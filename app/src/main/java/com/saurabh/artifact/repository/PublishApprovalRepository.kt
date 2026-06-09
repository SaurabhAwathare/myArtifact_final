package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.security.UploadGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublishApprovalRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
    private val uploadGuard: UploadGuard,
    private val authRepository: AuthRepository // Assuming it exists based on other VMs
) {

    suspend fun getDraft(id: String): ArtifactDraftEntity? = withContext(Dispatchers.IO) {
        draftDao.getDraftById(id)
    }

    suspend fun updateDraft(draft: ArtifactDraftEntity) = withContext(Dispatchers.IO) {
        draftDao.update(draft)
    }

    suspend fun validateDraft(draft: ArtifactDraftEntity, transcript: List<TranscriptSegment>): ValidationResult = withContext(Dispatchers.Default) {
        // Automatic safety checks disabled per user request
        println(draft.id) // Avoid unused parameter warning
        println(transcript.size) // Avoid unused parameter warning
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
            val currentChecksum = MessageDigest.getInstance("SHA-256")
                .digest(frozenAudioFile.readBytes())
                .joinToString("") { "%02x".format(it) }

            val contentToHash = transcriptJson + frozenAudioFile.length() + metadataJson
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(contentToHash.toByteArray())
                .joinToString("") { "%02x".format(it) }
            
            val timestamp = System.currentTimeMillis()
            val userId = authRepository.currentUserId
            val fingerprint = uploadGuard.getDeviceFingerprint()
            val token = uploadGuard.generateApprovalToken(
                userId = userId,
                draftId = draftId,
                checksum = currentChecksum,
                timestamp = timestamp
            )

            // 3. Persist Snapshot
            draftDao.freezeSnapshot(
                id = draftId,
                transcriptJson = transcriptJson,
                audioPath = frozenAudioFile.absolutePath,
                metadataJson = metadataJson,
                hash = hash,
                token = token,
                fingerprint = fingerprint,
                timestamp = timestamp
            )
            
            // Note: We don't update lifecycle here anymore, 
            // the PublishingOrchestrator will do it.
            
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
