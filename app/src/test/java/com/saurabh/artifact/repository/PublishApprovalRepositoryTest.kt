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
    
    private lateinit var repository: PublishApprovalRepository

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        
        repository = PublishApprovalRepository(context, { draftDao }, uploadGuard, authRepository)
        
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
}
