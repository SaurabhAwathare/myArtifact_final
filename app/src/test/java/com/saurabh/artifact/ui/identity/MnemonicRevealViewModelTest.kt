package com.saurabh.artifact.ui.identity

import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.util.OnboardingManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MnemonicRevealViewModelTest {

    private val backupManager = mockk<BackupEncryptionManager>(relaxed = true)
    private val databaseManager = mockk<DatabaseEncryptionManager>(relaxed = true)
    private val onboardingManager = mockk<OnboardingManager>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    
    private lateinit var viewModel: MnemonicRevealViewModel

    @Before
    fun setup() {
        viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger
        )
    }

    @Test
    fun `mnemonic is generated on init`() {
        val words = viewModel.mnemonicWords.value
        assertEquals(12, words.size)
    }

    @Test
    fun `completeSetup successfully creates wrapper and saves locally`() = runTest {
        coEvery { databaseManager.createRecoveryWrapper(any()) } returns Result.success(Unit)
        
        var successCalled = false
        viewModel.completeSetup { successCalled = true }
        
        coVerify { databaseManager.createRecoveryWrapper(any()) }
        coVerify { backupManager.saveMnemonic(any()) }
        coVerify { onboardingManager.setMnemonicSaved(true) }
        assertTrue(successCalled)
    }

    @Test
    fun `completeSetup handles wrapper failure`() = runTest {
        coEvery { databaseManager.createRecoveryWrapper(any()) } returns Result.failure(Exception("Wrapper failed"))
        
        viewModel.completeSetup {}
        
        assertTrue(viewModel.setupError.value?.contains("Wrapper failed") == true)
        coVerify(exactly = 0) { onboardingManager.setMnemonicSaved(true) }
    }
}
