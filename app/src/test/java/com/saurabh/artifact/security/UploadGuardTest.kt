package com.saurabh.artifact.security

import android.content.Context
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.util.FileIntegrity
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UploadGuardTest {

    private val mockContext = mockk<Context>(relaxed = true)
    private lateinit var uploadGuard: UploadGuard
    
    private val testUserId = "user123"
    private val testDraftId = "draft456"
    private val testChecksum = "abc123checksum"
    private val testFingerprint = "test_device_id"
    private val testTimestamp = 1623250000000L

    @Before
    fun setup() {
        uploadGuard = spyk(UploadGuard(mockContext))
        every { uploadGuard.getDeviceFingerprint() } returns testFingerprint
        
        mockkObject(FileIntegrity)
    }

    @Test
    fun `generateApprovalToken produces consistent hash`() {
        val raw = "$testUserId:$testDraftId:$testChecksum:$testTimestamp:$testFingerprint"
        val expectedHash = "mocked_hash"
        every { FileIntegrity.hashString(raw) } returns expectedHash

        val token1 = uploadGuard.generateApprovalToken(testUserId, testDraftId, testChecksum, testTimestamp)
        val token2 = uploadGuard.generateApprovalToken(testUserId, testDraftId, testChecksum, testTimestamp)
        
        assertEquals(expectedHash, token1)
        assertEquals(token1, token2)
    }

    @Test
    fun `validateApproval returns true for valid matching draft`() {
        val audioPath = "dummy/path"
        val token = "valid_token"
        
        val draft = ArtifactDraftEntity(
            id = testDraftId,
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = token,
            deviceFingerprint = testFingerprint,
            publishApprovalTimestamp = testTimestamp
        )

        every { FileIntegrity.calculateChecksum(audioPath) } returns testChecksum
        every { uploadGuard.generateApprovalToken(testUserId, testDraftId, testChecksum, testTimestamp) } returns token

        val result = uploadGuard.validateApproval(draft, testUserId)
        
        assertTrue(result)
    }

    @Test
    fun `validateApproval returns false when checksum mismatch`() {
        val audioPath = "dummy/path"
        
        val draft = ArtifactDraftEntity(
            id = testDraftId,
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = "some_token",
            deviceFingerprint = testFingerprint,
            publishApprovalTimestamp = testTimestamp
        )

        every { FileIntegrity.calculateChecksum(audioPath) } returns "DIFFERENT_CHECKSUM"

        val result = uploadGuard.validateApproval(draft, testUserId)
        
        assertFalse(result)
    }

    @Test
    fun `validateApproval returns false when token mismatch`() {
        val audioPath = "dummy/path"
        
        val draft = ArtifactDraftEntity(
            id = testDraftId,
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = "WRONG_TOKEN",
            deviceFingerprint = testFingerprint,
            publishApprovalTimestamp = testTimestamp
        )

        every { FileIntegrity.calculateChecksum(audioPath) } returns testChecksum
        every { uploadGuard.generateApprovalToken(testUserId, testDraftId, testChecksum, testTimestamp) } returns "CORRECT_TOKEN"

        val result = uploadGuard.validateApproval(draft, testUserId)
        
        assertFalse(result)
    }

    @Test
    fun `validateApproval returns false when fingerprint mismatch`() {
        val audioPath = "dummy/path"
        val token = "valid_token"
        
        val draft = ArtifactDraftEntity(
            id = testDraftId,
            localAudioPath = audioPath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH,
            checksum = testChecksum,
            approvalToken = token,
            deviceFingerprint = "OLD_DEVICE_ID",
            publishApprovalTimestamp = testTimestamp
        )

        every { FileIntegrity.calculateChecksum(audioPath) } returns testChecksum
        every { uploadGuard.generateApprovalToken(testUserId, testDraftId, testChecksum, testTimestamp) } returns token

        val result = uploadGuard.validateApproval(draft, testUserId)
        
        assertFalse(result)
    }
}
