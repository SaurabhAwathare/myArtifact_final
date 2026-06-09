package com.saurabh.artifact.security

import android.content.Context
import android.annotation.SuppressLint
import android.provider.Settings
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.util.FileIntegrity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadGuard @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /**
     * Generates an integrity-bound verification token for the approval action.
     * Includes the file checksum to prevent post-approval file swapping.
     */
    fun generateApprovalToken(userId: String, draftId: String, checksum: String, timestamp: Long): String {
        val deviceId = getDeviceFingerprint()
        val raw = "$userId:$draftId:$checksum:$timestamp:$deviceId"
        return FileIntegrity.hashString(raw)
    }

    /**
     * Validates that the draft is in a state allowed for upload and the token is correct.
     * Performs a strict integrity check on the frozen file.
     */
    fun validateApproval(draft: ArtifactDraftEntity, userId: String): Boolean {
        // 1. State check
        if (draft.lifecycle != ArtifactLifecycle.READY_TO_PUBLISH && draft.lifecycle != ArtifactLifecycle.PUBLISHED) {
            return false
        }

        // 2. Integrity check: Verify current file matches the recorded checksum
        val audioPath = draft.frozenAudioPath ?: draft.localAudioPath
        val currentChecksum = FileIntegrity.calculateChecksum(audioPath)
        
        if (draft.checksum != null && draft.checksum != currentChecksum) {
            return false
        }

        // 3. Token check: Verify the approval token matches the current state
        val expectedToken = generateApprovalToken(
            userId = userId,
            draftId = draft.id,
            checksum = currentChecksum,
            timestamp = draft.publishApprovalTimestamp ?: 0L
        )
        
        if (draft.approvalToken != expectedToken) return false

        // 4. Device check
        if (draft.deviceFingerprint != getDeviceFingerprint()) return false

        return true
    }

    @SuppressLint("HardwareIds")
    fun getDeviceFingerprint(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }
}
