package com.saurabh.artifact.security

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.subtle.AesGcmJce
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DatabaseRecoveryTest {

    private lateinit var context: Context
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private lateinit var encryptionManager: DatabaseEncryptionManager
    private val mockAead = mockk<Aead>()
    private lateinit var testDataStore: DataStore<Preferences>
    private val testScope = TestScope(UnconfinedTestDispatcher())
    
    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val testPassphrase = "test-database-passphrase-32-bytes!!".toByteArray().sliceArray(0 until 32)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        testDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { context.preferencesDataStoreFile("test_db_encryption_prefs") }
        )

        // Mock Tink's AeadConfig
        mockkStatic(AeadConfig::class)
        every { AeadConfig.register() } just Runs
        
        encryptionManager = spyk(DatabaseEncryptionManager(context, testDataStore, diagnosticLogger))
        
        // Mock the internal googleAEAD delegate
        val field = DatabaseEncryptionManager::class.java.getDeclaredField("googleAEAD\$delegate")
        field.isAccessible = true
        field.set(encryptionManager, lazy { mockAead })
        
        // Mock database existence and validation
        every { encryptionManager["validatePassphrase"](any<ByteArray>()) } returns true
    }

    @After
    fun teardown() {
        unmockkAll()
        File(context.filesDir, "datastore").deleteRecursively()
    }

    @Test
    fun `preload returns Success when Keystore decryption works`() = runTest {
        // Setup: Ensure a passphrase exists and is encrypted by mock Keystore
        every { mockAead.encrypt(any(), any()) } returns "encrypted".toByteArray()
        every { mockAead.decrypt(any(), any()) } returns testPassphrase
        
        // This will call generateAndStoreNewPassphrase internally
        encryptionManager.preload() 
        
        val result = encryptionManager.preload()
        assertEquals(PreloadResult.Success, result)
        assertArrayEquals(testPassphrase, encryptionManager.getDatabasePassphrase())
    }

    @Test
    fun `preload returns RecoveryRequired when Keystore fails but wrapper exists`() = runTest {
        // 1. Setup: Valid Keystore and Wrapper
        every { mockAead.encrypt(any(), any()) } returns "encrypted".toByteArray()
        every { mockAead.decrypt(any(), any()) } returns testPassphrase
        encryptionManager.preload()
        encryptionManager.createRecoveryWrapper(testMnemonic)
        
        // 2. Simulate Keystore decryption failure (e.g. new device)
        every { mockAead.decrypt(any(), any()) } throws Exception("Keystore key missing")
        
        // Clear cache
        val field = DatabaseEncryptionManager::class.java.getDeclaredField("cachedPassphrase")
        field.isAccessible = true
        field.set(encryptionManager, null)
        
        val result = encryptionManager.preload()
        assertEquals(PreloadResult.RecoveryRequired, result)
    }

    @Test
    fun `tryRecovery successfully unwraps passphrase and re-binds Keystore`() = runTest {
        // 1. Setup: Valid wrapper exists
        encryptionManager.createRecoveryWrapper(testMnemonic)
        val originalPassphrase = encryptionManager.getDatabasePassphrase()
        
        // 2. Clear cache to simulate fresh start
        val field = DatabaseEncryptionManager::class.java.getDeclaredField("cachedPassphrase")
        field.isAccessible = true
        field.set(encryptionManager, null)
        
        // 3. Perform recovery
        val recoveryResult = encryptionManager.tryRecovery(testMnemonic)
        assertTrue(recoveryResult.isSuccess)
        
        // 4. Verify passphrase matches and is cached
        assertArrayEquals(originalPassphrase, encryptionManager.getDatabasePassphrase())
        
        // 5. Verify re-binding: Keystore save was called
        coVerify { encryptionManager.saveEncryptedPassphrase(originalPassphrase) }
    }

    @Test
    fun `tryRecovery fails with incorrect mnemonic`() = runTest {
        encryptionManager.createRecoveryWrapper(testMnemonic)
        
        val wrongMnemonic = "wrong words here ..."
        val recoveryResult = encryptionManager.tryRecovery(wrongMnemonic)
        
        assertTrue(recoveryResult.isFailure)
        // Verify database remains untouched (no generateAndStoreNewPassphrase call)
        coVerify(exactly = 0) { encryptionManager.generateAndStoreNewPassphrase() }
    }

    @Test
    fun `preload renames database only when no recovery option exists`() = runTest {
        // Setup: No wrapper in DataStore
        // Simulate Keystore failure
        every { mockAead.decrypt(any(), any()) } throws Exception("Keystore fail")
        
        val result = encryptionManager.preload()
        
        // Success because it fallbacks to generateAndStoreNewPassphrase which returns a result
        // Wait, preload returns Success if it managed to get A passphrase.
        assertEquals(PreloadResult.Success, result) 
        coVerify { encryptionManager.renameCorruptedDatabase() }
    }
}
