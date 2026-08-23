package com.saurabh.artifact.security

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.util.OnboardingManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EndToEndRecoveryTest {

    private lateinit var context: Context
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val mockAead = mockk<Aead>()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var encryptionManager: DatabaseEncryptionManager
    private lateinit var onboardingManager: OnboardingManager
    
    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val testPassphrase = "test-database-passphrase-32-bytes!!".toByteArray().sliceArray(0 until 32)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Setup OnboardingManager with a real (test) DataStore
        onboardingManager = OnboardingManager(context)
        
        // Setup DatabaseEncryptionManager with a real (test) DataStore
        val dbEncryptionDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { context.preferencesDataStoreFile("test_db_encryption_prefs") }
        )
        
        encryptionManager = spyk(DatabaseEncryptionManager(context, dbEncryptionDataStore, diagnosticLogger))
        
        // Mock Tink's AeadConfig
        mockkStatic(AeadConfig::class)
        every { AeadConfig.register() } just Runs
        
        // Mock the internal googleAEAD delegate
        val field = DatabaseEncryptionManager::class.java.getDeclaredField("googleAEAD\$delegate")
        field.isAccessible = true
        field.set(encryptionManager, lazy { mockAead })
        
        // Default: Keystore works
        every { mockAead.encrypt(any(), any()) } returns "encrypted-data".toByteArray()
        every { mockAead.decrypt(any(), any()) } returns testPassphrase
        
        // Mock database validation
        every { encryptionManager.validatePassphrase(any()) } returns true
    }

    @After
    fun teardown() {
        unmockkAll()
        File(context.filesDir, "datastore").deleteRecursively()
    }

    @Test
    fun `Full Lifecycle - Generation to Cross-Device Recovery`() = testScope.runTest {
        // --- DEVICE A ---
        // 1. Initial Setup
        val preloadResult = encryptionManager.preload()
        assertEquals(PreloadResult.InitialSetup, preloadResult)
        val originalPassphrase = encryptionManager.getDatabasePassphrase()
        
        // 2. Mnemonic Generation & Wrapper Creation
        encryptionManager.createRecoveryWrapper(testMnemonic)
        assertTrue(encryptionManager.isRecoverySetup.first())
        
        // --- SIMULATE BACKUP & RESTORE TO DEVICE B ---
        // We keep the DataStore file (simulating restore) but break the Keystore (new device)
        every { mockAead.decrypt(any(), any()) } throws Exception("Keystore key not found")
        
        // Clear in-memory cache
        val cacheField = DatabaseEncryptionManager::class.java.getDeclaredField("cachedPassphrase")
        cacheField.isAccessible = true
        cacheField.set(encryptionManager, null)
        
        // --- DEVICE B ---
        // 3. Startup on Device B
        val restoredPreload = encryptionManager.preload()
        assertEquals(PreloadResult.RecoveryRequired, restoredPreload)
        
        // 4. User enters Mnemonic
        val recoveryResult = encryptionManager.tryRecovery(testMnemonic)
        assertTrue(recoveryResult.isSuccess)
        
        // 5. Verification: original passphrase recovered and Keystore re-bound
        assertArrayEquals(originalPassphrase, encryptionManager.getDatabasePassphrase())
        
        // Re-enable mock Keystore for Device B
        every { mockAead.decrypt(any(), any()) } returns originalPassphrase
        
        // 6. Verify subsequent startup is seamless
        cacheField.set(encryptionManager, null)
        val subsequentPreload = encryptionManager.preload()
        assertEquals(PreloadResult.Success, subsequentPreload)
    }

    @Test
    fun `Incorrect Mnemonic does not destroy database`() = testScope.runTest {
        // 1. Setup Device A with wrapper
        encryptionManager.preload()
        encryptionManager.createRecoveryWrapper(testMnemonic)
        
        // 2. Simulate Device B (Keystore fail)
        every { mockAead.decrypt(any(), any()) } throws Exception("Keystore fail")
        val cacheField = DatabaseEncryptionManager::class.java.getDeclaredField("cachedPassphrase")
        cacheField.isAccessible = true
        cacheField.set(encryptionManager, null)
        
        encryptionManager.preload()
        
        // 3. Wrong Mnemonic
        val result = encryptionManager.tryRecovery("wrong mnemonic phrase ...")
        assertTrue(result.isFailure)
        
        // 4. Verify database is NOT wiped/renamed
        coVerify(exactly = 0) { encryptionManager.generateAndStoreNewPassphrase() }
        verify(exactly = 0) { encryptionManager.renameCorruptedDatabase() }
        
        // 5. Correct Mnemonic still works
        val secondTry = encryptionManager.tryRecovery(testMnemonic)
        assertTrue(secondTry.isSuccess)
    }
}
