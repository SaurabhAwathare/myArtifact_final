package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.ValidationReason
import com.saurabh.artifact.security.UploadGuard
import com.saurabh.artifact.util.SecureString
import dagger.Lazy
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
    private val draftDao: Lazy<DraftDao>,
    private val uploadGuard: UploadGuard,
    private val authRepository: AuthRepository,
    private val identityScout: com.saurabh.artifact.domain.IdentityScout,
) {

    suspend fun getDraft(id: String): ArtifactDraftEntity? = withContext(Dispatchers.IO) {
        val userId = authRepository.currentUserId
        if (userId.isEmpty()) return@withContext null
        draftDao.get().getDraftById(id, userId)
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

            // 2. Deterministic Validation before freezing
            val validation = validateDraft(draft, transcript)
            if (!validation.isValid) {
                Log.w("PublishApprovalRepo", "Validation failed: ${validation.errorCode} - ${validation.errorMessage}")
                return@withContext Result.failure(AppError.InvalidInput(validation.errorMessage ?: "Validation failed"))
            }

            approveAndFreeze(draftId, transcript)
        } catch (e: Exception) {
            Log.e("PublishApprovalRepo", "Auto-approval failed.", e)
            Result.failure(e)
        }
    }

    suspend fun validateDraft(draft: ArtifactDraftEntity, transcript: List<TranscriptSegment>): ValidationResult = withContext(Dispatchers.Default) {
        val title = draft.title ?: ""
        
        // 1. Title PII Scan (UI Bypass Prevention)
        val user = authRepository.currentUser.value
        
        val realName = user?.displayName?.let { SecureString.fromString(it) }
        val email = user?.email?.let { SecureString.fromString(it) }
        
        try {
            val leaks = identityScout.detectLeaks(title, realName, email)
            if (leaks.isNotEmpty()) {
                return@withContext ValidationResult(
                    isValid = false,
                    errorCode = "TITLE_PII_DETECTED",
                    errorMessage = "Your title contains sensitive identity information. Please use a more anonymous title.",
                    hasSensitiveInfo = true,
                    sensitiveFlagCount = leaks.size
                )
            }

            // 2. Transcript PII Scan (Phase 10: Responsible Anonymity)
            for (segment in transcript) {
                val transcriptLeaks = identityScout.detectLeaks(segment.text, realName, email)
                if (transcriptLeaks.any { 
                    it.reason == ValidationReason.REAL_NAME || 
                    it.reason == ValidationReason.EMAIL_ADDRESS || 
                    it.reason == ValidationReason.PHONE_NUMBER 
                }) {
                    return@withContext ValidationResult(
                        isValid = false,
                        errorCode = "TRANSCRIPT_PII_DETECTED",
                        errorMessage = "Your transcript contains sensitive identity information. Please redact it before publishing.",
                        hasSensitiveInfo = true
                    )
                }
            }
        } finally {
            realName?.clear()
            email?.clear()
        }

        // 2. Audio File Deterministic Checks
        val audioFile = File(draft.localAudioPath)
        
        if (!audioFile.exists()) {
            return@withContext ValidationResult(
                isValid = false,
                errorCode = "AUDIO_FILE_MISSING",
                errorMessage = "The recording file could not be found."
            )
        }

        if (!audioFile.canRead()) {
            return@withContext ValidationResult(
                isValid = false,
                errorCode = "AUDIO_FILE_READ_ERROR",
                errorMessage = "The recording file is not readable."
            )
        }

        val fileSize = audioFile.length()
        if (fileSize == 0L) {
            return@withContext ValidationResult(
                isValid = false,
                errorCode = "AUDIO_FILE_EMPTY",
                errorMessage = "The recording is empty."
            )
        }

        // Deterministic Safety Bound: 100MB (~2 hours of high quality AAC/M4A)
        if (fileSize > 100 * 1024 * 1024) {
            return@withContext ValidationResult(
                isValid = false,
                errorCode = "AUDIO_FILE_TOO_LARGE",
                errorMessage = "The recording exceeds the maximum size limit."
            )
        }

        // 3. Duration Check (Min 3 seconds)
        if (draft.durationMs < 3000) {
            return@withContext ValidationResult(
                isValid = false,
                errorCode = "AUDIO_DURATION_TOO_SHORT",
                errorMessage = "Artifacts must be at least 3 seconds long."
            )
        }

        // 4. Lightweight Container Validation (Magic Bytes)
        val isFormatValid = try {
            val buffer = ByteArray(16)
            val inputStream = if (draft.isEncrypted) {
                com.saurabh.artifact.security.SecurityArchitecture.openDecryptingStream(context, audioFile)
            } else {
                audioFile.inputStream()
            }
            
            inputStream.use { it.read(buffer) }
            
            // ftyp check (M4A/MP4): bytes 4-7 are 'ftyp' (66 74 79 70)
            val isM4A = buffer.size >= 8 && 
                        buffer[4] == 0x66.toByte() && buffer[5] == 0x74.toByte() && 
                        buffer[6] == 0x79.toByte() && buffer[7] == 0x70.toByte()
            
            // RIFF check (WAV): bytes 0-3 are 'RIFF' (52 49 46 46)
            val isWAV = buffer.size >= 4 && 
                        buffer[0] == 0x52.toByte() && buffer[1] == 0x49.toByte() && 
                        buffer[2] == 0x46.toByte() && buffer[3] == 0x46.toByte()

            isM4A || isWAV
        } catch (e: Exception) {
            Log.e("PublishApprovalRepo", "Failed to verify audio container", e)
            false
        }

        if (!isFormatValid) {
            return@withContext ValidationResult(
                isValid = false,
                errorCode = "AUDIO_FORMAT_INVALID",
                errorMessage = "The recording format is unrecognized or corrupted."
            )
        }

        ValidationResult(isValid = true)
    }

    suspend fun approveAndFreeze(draftId: String, transcript: List<TranscriptSegment>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authRepository.currentUserId
            if (userId.isEmpty()) return@withContext Result.failure(AppError.Unauthenticated())

            val draft = draftDao.get().getDraftById(draftId, userId) 
                ?: return@withContext Result.failure(AppError.NotFound("Draft", draftId))
            
            // Phase 10: Mandatory Sanitization at the freeze boundary
            val user = authRepository.currentUser.value
            val realName = user?.displayName?.let { SecureString.fromString(it) }
            val email = user?.email?.let { SecureString.fromString(it) }
            
            val sanitizedTranscript = try {
                sanitizeTranscript(transcript, realName, email)
            } finally {
                realName?.clear()
                email?.clear()
            }

            // 1. Generate Immutable Snapshot
            val transcriptJson = if (sanitizedTranscript.isEmpty()) null else Json.encodeToString(sanitizedTranscript)
            val frozenAudioFile = File(context.filesDir, "frozen_audio/${draftId}_approved.m4a").apply {
                parentFile?.mkdirs()
            }
            
            File(draft.localAudioPath).copyTo(frozenAudioFile, overwrite = true)
            
            // 2. Generate Approval Token (Streaming Checksum to prevent OOM)
            val currentChecksum = com.saurabh.artifact.util.FileIntegrity.calculateChecksum(frozenAudioFile.absolutePath)
            
            if (currentChecksum.isEmpty()) {
                throw Exception("Failed to calculate checksum for frozen audio file.")
            }

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
            val secureTranscript = transcriptJson?.let { SecureString.fromString(it) }
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

    private fun sanitizeTranscript(
        transcript: List<TranscriptSegment>,
        realName: SecureString?,
        email: SecureString?
    ): List<TranscriptSegment> {
        if (transcript.isEmpty()) return transcript

        return transcript.map { segment ->
            // PRESERVE: Existing manual redactions
            if (segment.text == "[REDACTED]") return@map segment

            val leaks = identityScout.detectLeaks(segment.text, realName, email)
            
            // REDACT: High-confidence PII matching user profile
            val hasHighRiskLeak = leaks.any { 
                it.reason == ValidationReason.REAL_NAME || 
                it.reason == ValidationReason.EMAIL_ADDRESS || 
                it.reason == ValidationReason.PHONE_NUMBER 
            }

            if (hasHighRiskLeak) {
                segment.copy(text = "[REDACTED]", words = emptyList())
            } else {
                segment
            }
        }
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val hasSensitiveInfo: Boolean = false,
        val isHighRisk: Boolean = false,
        val sensitiveFlagCount: Int = 0
    )
}
