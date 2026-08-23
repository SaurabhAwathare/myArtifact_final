package com.saurabh.artifact.ui.recovery

import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.security.DatabaseEncryptionManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MnemonicRestoreViewModelTest {

    private val encryptionManager = mockk<DatabaseEncryptionManager>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private lateinit var viewModel: MnemonicRestoreViewModel

    @Before
    fun setup() {
        viewModel = MnemonicRestoreViewModel(encryptionManager, diagnosticLogger)
    }

    @Test
    fun `attemptRecovery success transitions to Success state`() = runTest {
        coEvery { encryptionManager.tryRecovery(any()) } returns Result.success(Unit)
        
        viewModel.onMnemonicChange("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        
        var successCalled = false
        viewModel.attemptRecovery { successCalled = true }
        
        assertEquals(RecoveryUiState.Success, viewModel.uiState.value)
        assertTrue(successCalled)
    }

    @Test
    fun `attemptRecovery failure transitions to Error state`() = runTest {
        val errorMessage = "Invalid phrase"
        coEvery { encryptionManager.tryRecovery(any()) } returns Result.failure(Exception(errorMessage))
        
        viewModel.onMnemonicChange("wrong phrase")
        viewModel.attemptRecovery {}
        
        assertTrue(viewModel.uiState.value is RecoveryUiState.Error)
    }

    @Test
    fun `startFresh calls encryptionManager and onConfirm`() = runTest {
        var confirmCalled = false
        viewModel.startFresh { confirmCalled = true }
        
        coVerify { encryptionManager.generateAndStoreNewPassphrase() }
        assertTrue(confirmCalled)
    }
}
