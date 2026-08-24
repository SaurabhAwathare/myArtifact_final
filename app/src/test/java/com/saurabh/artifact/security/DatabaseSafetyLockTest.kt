package com.saurabh.artifact.security

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DatabaseSafetyLockTest {

    private lateinit var context: Context
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private lateinit var encryptionManager: DatabaseEncryptionManager
    private val mockAead = mockk<Aead>()
    private lateinit var testDataStore: DataStore<Preferences>
    private val testScope = TestScope(UnconfinedTestDispatcher())
    
    private val RECOVERY_WRAPPER_KEY = stringPreferencesKey("recovery_passphrase_wrapper")
    private val DB_PASSPHRASE_KEY = stringPreferencesKey("db_passphrase")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        testDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { context.preferencesDataStoreFile("test_safety_lock_prefs") }
        )

        encryptionManager = spyk(DatabaseEncryptionManager(context, testDataStore, diagnosticLogger))
        
        val field = DatabaseEncryptionManager::class.java.getDeclaredField("googleAEAD\$delegate")
        field.isAccessible = true
        field.set(encryptionManager, lazy { mockAead })
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test(expected = DatabaseLockedException::class)
    fun `getDatabasePassphrase should throw DatabaseLockedException if decryption fails and recovery exists`() = runTest {
        // 1. Setup: Wrapper exists but decryption fails
        testDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[RECOVERY_WRAPPER_KEY] = "exists"
                this[DB_PASSPHRASE_KEY] = "encrypted_but_stale"
            }.toPreferences()
        }
        
        every { mockAead.decrypt(any(), any()) } throws Exception("Keystore invalid")

        // 2. Act: This should throw DatabaseLockedException
        encryptionManager.getDatabasePassphrase()
    }

    @Test
    fun `getDatabasePassphrase should NOT rename database if recovery exists`() = runTest {
        // 1. Setup
        testDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[RECOVERY_WRAPPER_KEY] = "exists"
                this[DB_PASSPHRASE_KEY] = "encrypted"
            }.toPreferences()
        }
        every { mockAead.decrypt(any(), any()) } throws Exception("Keystore invalid")

        // 2. Act
        try {
            encryptionManager.getDatabasePassphrase()
        } catch (e: DatabaseLockedException) {
            // Expected
        }

        // 3. Assert: renameCorruptedDatabase was NOT called
        verify(exactly = 0) { encryptionManager.renameCorruptedDatabase() }
        coVerify(exactly = 0) { encryptionManager.generateAndStoreNewPassphrase() }
    }

    @Test
    fun `getDatabasePassphrase should proceed with wipe if NO recovery exists`() = runTest {
        // 1. Setup: Passphrase exists but NO wrapper, decryption fails
        testDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[DB_PASSPHRASE_KEY] = "encrypted"
            }.toPreferences()
        }
        every { mockAead.decrypt(any(), any()) } throws Exception("Keystore invalid")
        
        // Mock internal wipe methods
        coEvery { encryptionManager.generateAndStoreNewPassphrase() } returns "new".toByteArray()

        // 2. Act
        encryptionManager.getDatabasePassphrase()

        // 3. Assert: Fallback wipe was triggered (Existing behavior)
        coVerify { encryptionManager.generateAndStoreNewPassphrase() }
    }
}
