package com.saurabh.artifact.security

import android.content.Context
import android.provider.Settings
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactLifecycle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadGuard @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Generates a simple verification token for the approval action.
     * In a production environment, this should use HMAC-SHA256 with a key from Android Keystore.
     */
    fun generateApprovalToken(userId: String, draftId: String, timestamp: Long): String {
        val deviceId = getDeviceFingerprint()
        val raw = "$userId:$draftId:$timestamp:$deviceId"
        return hashString(raw)
    }

    /**
     * Validates that the draft is in a state allowed for upload and the token is correct.
     */
    fun validateApproval(draft: ArtifactDraftEntity, userId: String): Boolean {
        // 1. State check
        if (draft.lifecycle != ArtifactLifecycle.READY_TO_PUBLISH && draft.lifecycle != ArtifactLifecycle.PUBLISHED) {
            return false
        }

        // 2. Token check
        val expectedToken = generateApprovalToken(
            userId = userId,
            draftId = draft.id,
            timestamp = draft.publishApprovalTimestamp ?: 0L
        )
        
        if (draft.approvalToken != expectedToken) return false

        // 3. Device check
        if (draft.deviceFingerprint != getDeviceFingerprint()) return false

        return true
    }

    fun getDeviceFingerprint(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }
}
