package com.saurabh.artifact.repository

import android.content.Context
import com.google.firebase.auth.FirebaseUser
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.security.SecurityArchitecture
import com.saurabh.artifact.security.UploadGuard
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class PublishApprovalRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val draftDao = mockk<DraftDao>(relaxed = true)
    private val uploadGuard = mockk<UploadGuard>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val identityScout = com.saurabh.artifact.domain.IdentityScout()
    
    private lateinit var repository: PublishApprovalRepository

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        
        repository = PublishApprovalRepository(
            context = context,
            draftDao = { draftDao },
            uploadGuard = uploadGuard,
            authRepository = authRepository,
            identityScout = identityScout
        )
        
        mockkObject(SecurityArchitecture)
        
        // Mock current user for PII scan
        val firebaseUser = mockk<FirebaseUser>(relaxed = true)
        every { firebaseUser.displayName } returns "John Doe"
        every { firebaseUser.email } returns "john@example.com"
        every { authRepository.currentUser } returns MutableStateFlow(firebaseUser)
    }

    private fun createMockAudioFile(name: String, content: ByteArray): File {
        val file = tempFolder.newFile(name)
        file.writeBytes(content)
        return file
    }

    @Test
    fun `VALID_ARTIFACT - M4A`() = runBlocking {
        val audioFile = createMockAudioFile("test.m4a", byteArrayOf(0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70)) // ftyp at 4
        val draft = ArtifactDraftEntity(
            id = "d1",
            userId = "u1",
            title = "Valid Title",
            localAudioPath = audioFile.absolutePath,
            durationMs = 5000,
            isEncrypted = false
        )

        val result = repository.validateDraft(draft, emptyList())
        assertTrue("Validation should succeed for valid M4A", result.isValid)
    }

    @Test
    fun `VALID_ARTIFACT - WAV`() = runBlocking {
        val audioFile = createMockAudioFile("test.wav", byteArrayOf(0x52, 0x49, 0x46, 0x46)) // RIFF
        val draft = ArtifactDraftEntity(
            id = "d1",
            userId = "u1",
            title = "Valid Title",
            localAudioPath = audioFile.absolutePath,
            durationMs = 5000,
            isEncrypted = false
        )

        val result = repository.validateDraft(draft, emptyList())
        assertTrue("Validation should succeed for valid WAV", result.isValid)
    }

    @Test
    fun `TITLE_PII - should fail if title contains real name`() = runBlocking {
        val audioFile = createMockAudioFile("test.m4a", byteArrayOf(0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70))
        val draft = ArtifactDraftEntity(
            id = "d1",
            userId = "u1",
            title = "Story by John Doe", // Contains displayName
            localAudioPath = audioFile.absolutePath,
            durationMs = 5000
        )

        val result = repository.validateDraft(draft, emptyList())
        assertFalse("Validation should fail if title contains PII", result.isValid)
        assertEquals("TITLE_PII_DETECTED", result.errorCode)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `MISSING_AUDIO - should fail if file does not exist`() = runBlocking {
        val draft = ArtifactDraftEntity(
            id = "d1",
            userId = "u1",
            title = "Valid Title",
            localAudioPath = File(tempFolder.root, "missing.m4a").absolutePath,
            durationMs = 5000
        )

        val result = repository.validateDraft(draft, emptyList())
        assertFalse("Validation should fail if audio file is missing", result.isValid)
        assertEquals("AUDIO_FILE_MISSING", result.errorCode)
    }

    @Test
    fun `EMPTY_AUDIO - should fail if file size is 0`() = runBlocking {
        val audioFile = createMockAudioFile("empty.m4a", byteArrayOf())
        val draft = ArtifactDraftEntity(
            id = "d1",
            userId = "u1",
            title = "Valid Title",
            localAudioPath = audioFile.absolutePath,
            durationMs = 5000
        )

        val result = repository.validateDraft(draft, emptyList())
        assertFalse("Validation should fail if audio file is empty", result.isValid)
        assertEquals("AUDIO_FILE_EMPTY", result.errorCode)
    }

    @Test
    fun `SHORT_AUDIO - should fail if duration less than 3s`() = runBlocking {
        val audioFile = createMockAudioFile("test.m4a", byteArrayOf(0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70))
        val draft = ArtifactDraftEntity(
            id = "d1",
            userId = "u1",
            title = "Valid Title",
            localAudioPath = audioFile.absolutePath,
            durationMs = 2999
        )

        val result = repository.validateDraft(draft, emptyList())
        assertFalse("Validation should fail if duration is too short", result.isValid)
        assertEquals("AUDIO_DURATION_TOO_SHORT", result.errorCode)
    }

    @Test
    fun `OVERSIZED_AUDIO - should fail if file exceeds 100MB`() = runBlocking {
        val audioFile = tempFolder.newFile("large.m4a")
        RandomAccessFile(audioFile, "rw").use { it.setLength(101 * 1024 * 1024) }
        
        val draft = ArtifactDraftEntity(
            id = "d1",
            userId = "u1",
            title = "Valid Title",
            localAudioPath = audioFile.absolutePath,
            durationMs = 5000
        )

        val result = repository.validateDraft(draft, emptyList())
        assertFalse("Validation should fail if audio file is too large", result.isValid)
        assertEquals("AUDIO_FILE_TOO_LARGE", result.errorCode)
    }

    @Test
    fun `INVALID_FORMAT - should fail if no valid magic bytes`() = runBlocking {
        val audioFile = createMockAudioFile("garbage.bin", byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
        val draft = ArtifactDraftEntity(
            id = "d1",
            userId = "u1",
            title = "Valid Title",
            localAudioPath = audioFile.absolutePath,
            durationMs = 5000
        )

        val result = repository.validateDraft(draft, emptyList())
        assertFalse("Validation should fail for invalid format", result.isValid)
        assertEquals("AUDIO_FORMAT_INVALID", result.errorCode)
    }

    @Test
    fun `ENCRYPTED_AUDIO - should use decrypting stream`() = runBlocking {
        val audioFile = createMockAudioFile("encrypted.m4a", byteArrayOf(0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70))
        val draft = ArtifactDraftEntity(
            id = "d1",
            userId = "u1",
            title = "Valid Title",
            localAudioPath = audioFile.absolutePath,
            durationMs = 5000,
            isEncrypted = true
        )

        // Mock decrypting stream to return the file content
        every { SecurityArchitecture.openDecryptingStream(any(), any()) } returns audioFile.inputStream()

        val result = repository.validateDraft(draft, emptyList())
        assertTrue("Validation should succeed for encrypted valid audio", result.isValid)
        verify { SecurityArchitecture.openDecryptingStream(context, any()) }
    }

    @Test
    fun `FAILURE_STATE_INTEGRITY - validation failure should prevent freeze`() = runBlocking {
        val audioFile = createMockAudioFile("empty.m4a", byteArrayOf())
        val draftId = "d1"
        val userId = "u123"
        
        every { authRepository.currentUserId } returns userId
        coEvery { draftDao.getDraftById(draftId, userId) } returns ArtifactDraftEntity(
            id = draftId,
            userId = userId,
            title = "Valid Title",
            localAudioPath = audioFile.absolutePath,
            durationMs = 5000
        )

        val result = repository.approveAndFreezeAuto(draftId)
        
        assertTrue("Operation should fail when validation fails", result.isFailure)
        assertTrue("Should fail with InvalidInput", result.exceptionOrNull() is AppError.InvalidInput)
        
        // Verify side effects (freezeSnapshot and generateApprovalToken should NOT be called)
        coVerify(exactly = 0) { draftDao.freezeSnapshot(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { uploadGuard.generateApprovalToken(any(), any(), any(), any()) }
    }

    @Test
    fun `TRANSCRIPT_PII - should fail validation if segment contains user email`() = runBlocking {
        // Arrange
        val audioFile = createMockAudioFile("test.m4a", byteArrayOf(
            0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70.toByte() // ftyp m4a
        ))
        val draft = ArtifactDraftEntity(
            id = "d1", userId = "u1", localAudioPath = audioFile.absolutePath,
            durationMs = 5000, title = "Clean Title"
        )
        val transcript = listOf(
            com.saurabh.artifact.model.TranscriptSegment(text = "Hello, my email is john@example.com")
        )
        
        val mockUser = mockk<FirebaseUser>(relaxed = true) {
            every { email } returns "john@example.com"
        }
        every { authRepository.currentUser } returns MutableStateFlow(mockUser)

        // Act
        val result = repository.validateDraft(draft, transcript)

        // Assert
        assertFalse(result.isValid)
        assertEquals("TRANSCRIPT_PII_DETECTED", result.errorCode)
    }

    @Test
    fun `TRANSCRIPT_PII - freeze should REDACT sensitive info even if validation is bypassed`() = runTest {
        // Arrange
        val audioFile = createMockAudioFile("test.m4a", byteArrayOf(
            0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70.toByte() // ftyp m4a
        ))
        val draft = ArtifactDraftEntity(
            id = "d1", userId = "u1", localAudioPath = audioFile.absolutePath,
            durationMs = 5000, title = "Clean Title"
        )
        val transcript = listOf(
            com.saurabh.artifact.model.TranscriptSegment(text = "My name is John Doe")
        )
        
        val mockUser = mockk<FirebaseUser>(relaxed = true) {
            every { displayName } returns "John Doe"
            every { uid } returns "u1"
        }
        every { authRepository.currentUser } returns MutableStateFlow(mockUser)
        every { authRepository.currentUserId } returns "u1"
        coEvery { draftDao.getDraftById("d1", "u1") } returns draft

        // Act
        repository.approveAndFreeze("d1", transcript)

        // Assert
        val capturedJson = slot<com.saurabh.artifact.util.SecureString>()
        coVerify { 
            draftDao.freezeSnapshot(
                id = "d1",
                userId = "u1",
                transcriptJson = capture(capturedJson),
                any(), any(), any(), any()
            )
        }
        
        val json = capturedJson.captured.toUnsecureString()
        assertTrue("JSON should contain REDACTED", json.contains("[REDACTED]"))
        assertFalse("JSON should NOT contain real name", json.contains("John Doe"))
    }

    @Test
    fun `CHECKSUM_EQUIVALENCE - streaming should match in-memory hash`() = runBlocking {
        // Arrange
        val content = "Artifact streaming checksum verification content".toByteArray()
        val audioFile = createMockAudioFile("checksum_test.m4a", content)
        
        // Old manual way (in-memory)
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString("") { "%02x".format(it) }

        // Act
        val actual = com.saurabh.artifact.util.FileIntegrity.calculateChecksum(audioFile.absolutePath)

        // Assert
        assertEquals("Streaming checksum must match in-memory checksum", expected, actual)
    }

    @Test
    fun `LARGE_FILE_CHECKSUM - should process large file without OOM`() = runBlocking {
        // Arrange
        val largeFile = tempFolder.newFile("oom_test.m4a")
        // Use RandomAccessFile to create a 100MB file without allocating bytes in JVM heap
        RandomAccessFile(largeFile, "rw").use { it.setLength(100 * 1024 * 1024) }
        
        // Act & Assert
        try {
            val checksum = com.saurabh.artifact.util.FileIntegrity.calculateChecksum(largeFile.absolutePath)
            assertNotNull("Checksum should be calculated", checksum)
            assertNotEquals("Checksum should not be empty", "", checksum)
        } catch (_: OutOfMemoryError) {
            fail("Streaming checksum should not trigger OutOfMemoryError for 100MB file")
        }
    }

    @Test
    fun `FREEZE_FAILURE - empty checksum should fail operation`() = runTest {
        // Arrange
        val audioFile = createMockAudioFile("test.m4a", byteArrayOf(0, 1, 2))
        val draft = ArtifactDraftEntity(
            id = "d1", userId = "u1", localAudioPath = audioFile.absolutePath,
            durationMs = 5000, title = "Valid Title"
        )
        
        every { authRepository.currentUserId } returns "u1"
        coEvery { draftDao.getDraftById("d1", "u1") } returns draft
        
        // Mock FileIntegrity to fail
        mockkObject(com.saurabh.artifact.util.FileIntegrity)
        every { com.saurabh.artifact.util.FileIntegrity.calculateChecksum(any()) } returns ""

        // Act
        val result = repository.approveAndFreeze("d1", emptyList())

        // Assert
        assertTrue("Operation should fail if checksum is empty", result.isFailure)
        assertEquals("Failed to calculate checksum for frozen audio file.", result.exceptionOrNull()?.message)
        
        unmockkObject(com.saurabh.artifact.util.FileIntegrity)
    }
}
