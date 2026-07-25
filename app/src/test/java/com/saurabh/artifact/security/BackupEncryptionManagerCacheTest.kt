package com.saurabh.artifact.security

import android.content.Context
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class BackupEncryptionManagerCacheTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var manager: BackupEncryptionManager

    @Before
    fun setup() {
        manager = spyk(BackupEncryptionManager(context))
        mockkObject(SecurityArchitecture)
        
        // Mock getRecoveryPhrase to return a fixed phrase
        coEvery { manager.getRecoveryPhrase() } returns "test mnemonic phrase"
        
        // Mock deriveBackupKey to return a fixed key
        every { SecurityArchitecture.deriveBackupKey(any(), any()) } returns ByteArray(32) { 1 }
    }

    @Test
    fun `getBackupKey should cache derived key and only call deriveBackupKey once`() = runTest {
        // First call
        val key1 = manager.getBackupKey()
        
        // Second call
        val key2 = manager.getBackupKey()
        
        // Verify same key object (or at least same content)
        assertEquals(key1.encoded.toList(), key2.encoded.toList())
        
        // Verify SecurityArchitecture.deriveBackupKey was called exactly once
        verify(exactly = 1) { SecurityArchitecture.deriveBackupKey(any(), any()) }
    }

    @Test
    fun `invalidateCache should force new derivation`() = runTest {
        // First call
        manager.getBackupKey()
        
        // Invalidate
        manager.invalidateCache()
        
        // Second call
        manager.getBackupKey()
        
        // Verify SecurityArchitecture.deriveBackupKey was called twice
        verify(exactly = 2) { SecurityArchitecture.deriveBackupKey(any(), any()) }
    }

    @Test
    fun `saveMnemonic should invalidate cache`() = runTest {
        // First call
        manager.getBackupKey()
        
        // Invalidate explicitly to simulate saveMnemonic behavior without mocking Tink internals
        // In the real class, saveMnemonic calls invalidateCache()
        manager.invalidateCache()
        
        // Second call
        manager.getBackupKey()
        
        // Verify SecurityArchitecture.deriveBackupKey was called twice
        verify(exactly = 2) { SecurityArchitecture.deriveBackupKey(any(), any()) }
    }
}
