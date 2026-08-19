package com.saurabh.artifact.security

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.crypto.spec.SecretKeySpec

import dagger.Lazy

class DataExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private val draftDao = mockk<DraftDao>()
    private val encryptionManager = mockk<BackupEncryptionManager>()
    private val authRepository = mockk<AuthRepository>()
    private val contentResolver = mockk<ContentResolver>()

    private lateinit var dataExportManager: DataExportManager

    private companion object {
        private const val TEST_USER_ID = "test-user-id"
    }

    @Before
    fun setup() {
        StreamingAeadConfig.register()
        every { context.contentResolver } returns contentResolver
        dataExportManager = DataExportManager(context, Lazy { draftDao }, encryptionManager, authRepository)
    }

    @Test
    fun `test cryptographic domain separation`() {
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val backupSalt = "artifact_backup_v1_salt".toByteArray()
        val exportSalt = "artifact_export_v1_salt".toByteArray()

        val backupKey = SecurityArchitecture.deriveKey(phrase, backupSalt)
        val exportKey = SecurityArchitecture.deriveKey(phrase, exportSalt)

        assertFalse("Backup and Export keys must be different", backupKey.contentEquals(exportKey))
    }

    @Test
    fun `test secure export produced encrypted file and is decryptable`() = runBlocking {
        // 1. Setup mocks
        val userId = "test_user_123"
        val exportKeyBytes = ByteArray(32) { 0x42.toByte() }
        val exportKey = SecretKeySpec(exportKeyBytes, "AES")
        
        val draft = ArtifactDraftEntity(
            id = "draft_1",
            userId = TEST_USER_ID,
            localAudioPath = tempFolder.newFile("audio.wav").absolutePath,
            lifecycle = ArtifactLifecycle.READY_TO_PUBLISH
        )
        File(draft.localAudioPath).writeText("fake audio content")

        coEvery { draftDao.getAllDraftsByUserId(userId) } returns listOf(draft)
        coEvery { encryptionManager.getExportKey() } returns exportKey
        every { authRepository.currentUserId } returns userId

        val outputFile = tempFolder.newFile("export.artx")
        val uri = mockk<Uri>()
        every { contentResolver.openOutputStream(uri) } returns FileOutputStream(outputFile)

        // 2. Execute Export
        val result = dataExportManager.exportAllDrafts(uri)
        assertTrue(result.isSuccess)

        // 3. Verify the file is NOT a plain ZIP
        val isPlainZip = try {
            ZipInputStream(outputFile.inputStream()).use { it.nextEntry != null }
        } catch (e: Exception) {
            false
        }
        assertFalse("Exported file should not be a plain ZIP", isPlainZip)

        // 4. Decrypt and Verify Content
        val aad = "artifact_export_v1:$userId".toByteArray()
        val parameters = PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB as AesGcmHkdfStreamingParameters

        val keysetHandle = KeysetHandle.newBuilder()
            .addEntry(
                KeysetHandle.importKey(
                    AesGcmHkdfStreamingKey.create(
                        parameters,
                        SecretBytes.copyFrom(exportKeyBytes, InsecureSecretKeyAccess.get())
                    )
                ).makePrimary().withFixedId(1)
            )
            .build()

        val streamingAead = keysetHandle.getPrimitive(StreamingAead::class.java)

        streamingAead.newDecryptingStream(outputFile.inputStream(), aad).use { decryptedStream ->
            ZipInputStream(decryptedStream).use { zipIn ->
                val entry = zipIn.nextEntry
                assertNotNull(entry)
                assertTrue(entry!!.name.contains("metadata.json"))
                
                val metadata = zipIn.bufferedReader().readText()
                assertTrue(metadata.contains("draft_1"))
            }
        }
    }

    @Test
    fun `test export fails with wrong AAD`() = runBlocking {
        // ... similar setup as above ...
        val userId = "test_user_123"
        val exportKeyBytes = ByteArray(32) { 0x42.toByte() }
        val exportKey = SecretKeySpec(exportKeyBytes, "AES")
        coEvery { draftDao.getAllDraftsByUserId(userId) } returns emptyList()
        coEvery { encryptionManager.getExportKey() } returns exportKey
        every { authRepository.currentUserId } returns userId

        val outputFile = tempFolder.newFile("export_fail.artx")
        val uri = mockk<Uri>()
        every { contentResolver.openOutputStream(uri) } returns FileOutputStream(outputFile)

        dataExportManager.exportAllDrafts(uri)

        // Try decrypting with WRONG user ID in AAD
        val wrongAad = "artifact_export_v1:different_user".toByteArray()
        val parameters = PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB as AesGcmHkdfStreamingParameters

        val keysetHandle = KeysetHandle.newBuilder()
            .addEntry(
                KeysetHandle.importKey(
                    AesGcmHkdfStreamingKey.create(
                        parameters,
                        SecretBytes.copyFrom(exportKeyBytes, InsecureSecretKeyAccess.get())
                    )
                ).makePrimary().withFixedId(1)
            )
            .build()

        val streamingAead = keysetHandle.getPrimitive(StreamingAead::class.java)

        try {
            streamingAead.newDecryptingStream(outputFile.inputStream(), wrongAad).use { it.read() }
            fail("Should have thrown exception due to AAD mismatch")
        } catch (e: java.io.IOException) {
            // Expected
        }
    }

    @Test
    fun `test cross-account isolation during export`() = runBlocking {
        val userA = "user_a"
        val userB = "user_b"
        
        val draftA = ArtifactDraftEntity(id = "draft_a", userId = userA, localAudioPath = tempFolder.newFile("a.wav").absolutePath)
        val draftB = ArtifactDraftEntity(id = "draft_b", userId = userB, localAudioPath = tempFolder.newFile("b.wav").absolutePath)
        
        val exportKey = SecretKeySpec(ByteArray(32), "AES")
        
        // Setup: User A is logged in
        every { authRepository.currentUserId } returns userA
        coEvery { encryptionManager.getExportKey() } returns exportKey
        
        // DAO should ONLY be called with current user ID
        coEvery { draftDao.getAllDraftsByUserId(userA) } returns listOf(draftA)
        coEvery { draftDao.getAllDraftsByUserId(userB) } returns listOf(draftB)
        
        val outputFile = tempFolder.newFile("isolation_test.artx")
        val uri = mockk<Uri>()
        every { contentResolver.openOutputStream(uri) } returns FileOutputStream(outputFile)
        
        // Action: Perform export
        dataExportManager.exportAllDrafts(uri)
        
        // Verification: Decrypt and check entries
        val aad = "artifact_export_v1:$userA".toByteArray()
        val streamingAead = getStreamingAead(exportKey.encoded)
        
        streamingAead.newDecryptingStream(outputFile.inputStream(), aad).use { decryptedStream ->
            ZipInputStream(decryptedStream).use { zipIn ->
                var entryCount = 0
                var hasA = false
                var hasB = false
                
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (entry.name.contains("draft_a")) hasA = true
                    if (entry.name.contains("draft_b")) hasB = true
                    entryCount++
                    entry = zipIn.nextEntry
                }
                
                assertTrue("Should contain User A drafts", hasA)
                assertFalse("Must NOT contain User B drafts", hasB)
            }
        }
    }

    private fun getStreamingAead(keyBytes: ByteArray): StreamingAead {
        val parameters = PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB as AesGcmHkdfStreamingParameters
        val keysetHandle = KeysetHandle.newBuilder()
            .addEntry(
                KeysetHandle.importKey(
                    AesGcmHkdfStreamingKey.create(
                        parameters,
                        SecretBytes.copyFrom(keyBytes, InsecureSecretKeyAccess.get())
                    )
                ).makePrimary().withFixedId(1)
            )
            .build()
        return keysetHandle.getPrimitive(StreamingAead::class.java)
    }
}
