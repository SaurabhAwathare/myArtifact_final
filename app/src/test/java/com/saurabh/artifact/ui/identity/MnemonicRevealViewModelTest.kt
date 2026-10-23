package com.saurabh.artifact.ui.identity

import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.util.OnboardingManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MnemonicRevealViewModelTest {

    private val backupManager = mockk<BackupEncryptionManager>(relaxed = true)
    private val databaseManager = mockk<DatabaseEncryptionManager>(relaxed = true)
    private val onboardingManager = mockk<OnboardingManager>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { databaseManager.isRecoverySetup } returns flowOf(false)
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(false)
        every { authRepository.currentUserId } returns "user_123"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initial setup generates new phrase when no recovery setup exists`() = runTest {
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(false)

        val viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger,
            authRepository
        )
        testScheduler.runCurrent()

        assertFalse(viewModel.isExistingPhrase.value)
        assertEquals(12, viewModel.mnemonicWords.value.size)
        assertEquals(RecoveryStatus.Incomplete, viewModel.recoveryStatus.value)
    }

    @Test
    fun `completeSetup successfully creates wrapper and saves locally for initial setup`() = runTest {
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(false)
        coEvery { databaseManager.createRecoveryWrapper(any(), any()) } returns Result.success(Unit)

        val viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger,
            authRepository
        )
        testScheduler.runCurrent()

        var successCalled = false
        viewModel.completeSetup { successCalled = true }
        testScheduler.runCurrent()

        coVerify { databaseManager.createRecoveryWrapper("user_123", any()) }
        coVerify { backupManager.saveMnemonic("user_123", any()) }
        coVerify { onboardingManager.setMnemonicSaved("user_123", true) }
        assertTrue(successCalled)
        assertEquals(RecoveryStatus.Completed, viewModel.recoveryStatus.value)
    }

    @Test
    fun `completeSetup handles wrapper failure during initial setup`() = runTest {
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(false)
        coEvery { databaseManager.createRecoveryWrapper(any(), any()) } returns Result.failure(Exception("Wrapper failed"))

        val viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger,
            authRepository
        )
        testScheduler.runCurrent()

        viewModel.completeSetup {}
        testScheduler.runCurrent()

        assertTrue(viewModel.setupError.value?.contains("Wrapper failed") == true)
        coVerify(exactly = 0) { onboardingManager.setMnemonicSaved(any<String>(), any()) }
    }

    @Test
    fun `opening recovery screen with valid existing recovery material loads existing phrase`() = runTest {
        val testPhrase = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu"
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(true)
        coEvery { backupManager.getRecoveryPhrase(any()) } returns testPhrase
        coEvery { databaseManager.validateRecoveryPhrase(any(), testPhrase) } returns true

        val viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger,
            authRepository
        )
        testScheduler.runCurrent()

        assertTrue(viewModel.isExistingPhrase.value)
        assertEquals(12, viewModel.mnemonicWords.value.size)
        assertEquals("alpha", viewModel.mnemonicWords.value.first())
        assertEquals(null, viewModel.setupError.value)
        assertEquals(RecoveryStatus.Completed, viewModel.recoveryStatus.value)
    }

    @Test
    fun `completing existing phrase flow does not overwrite recovery wrapper or stored mnemonic`() = runTest {
        val testPhrase = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu"
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(true)
        coEvery { backupManager.getRecoveryPhrase(any()) } returns testPhrase
        coEvery { databaseManager.validateRecoveryPhrase(any(), testPhrase) } returns true

        val viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger,
            authRepository
        )
        testScheduler.runCurrent()

        var successCalled = false
        viewModel.completeSetup { successCalled = true }
        testScheduler.runCurrent()

        assertTrue(successCalled)
        coVerify(exactly = 0) { databaseManager.createRecoveryWrapper(any(), any()) }
    }

    @Test
    fun `inconsistent or unreadable recovery material sets error and does not regenerate phrase`() = runTest {
        val corruptedPhrase = "corrupted phrase"
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(true)
        coEvery { backupManager.getRecoveryPhrase(any()) } returns corruptedPhrase
        coEvery { databaseManager.validateRecoveryPhrase(any(), corruptedPhrase) } returns false

        val viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger,
            authRepository
        )
        testScheduler.runCurrent()

        assertTrue(viewModel.isExistingPhrase.value)
        assertTrue(viewModel.mnemonicWords.value.isEmpty())
        assertTrue(viewModel.setupError.value?.contains("inconsistent") == true)
        assertTrue(viewModel.recoveryStatus.value is RecoveryStatus.Error)

        viewModel.completeSetup {}
        testScheduler.runCurrent()

        coVerify(exactly = 0) { databaseManager.createRecoveryWrapper(any(), any()) }
    }

    @Test
    fun `missing stored phrase when wrapper exists sets PhraseMissing state and does not regenerate phrase`() = runTest {
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(true)
        coEvery { backupManager.getRecoveryPhrase(any()) } returns null

        val viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger,
            authRepository
        )
        testScheduler.runCurrent()

        assertTrue(viewModel.isExistingPhrase.value)
        assertTrue(viewModel.mnemonicWords.value.isEmpty())
        assertEquals(RecoveryStatus.PhraseMissing, viewModel.recoveryStatus.value)

        viewModel.completeSetup {}
        testScheduler.runCurrent()

        coVerify(exactly = 0) { databaseManager.createRecoveryWrapper(any(), any()) }
    }

    @Test
    fun `restoreExistingPhrase validates phrase and updates status to Completed`() = runTest {
        val testPhrase = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu"
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(true)
        coEvery { backupManager.getRecoveryPhrase(any()) } returns null
        coEvery { databaseManager.validateRecoveryPhrase(any(), testPhrase) } returns true

        val viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger,
            authRepository
        )
        testScheduler.runCurrent()

        assertEquals(RecoveryStatus.PhraseMissing, viewModel.recoveryStatus.value)

        var restored = false
        viewModel.restoreExistingPhrase(testPhrase) { restored = true }
        testScheduler.runCurrent()

        assertTrue(restored)
        coVerify { backupManager.saveMnemonic("user_123", testPhrase) }
        coVerify { onboardingManager.setMnemonicSaved("user_123", true) }
        assertEquals(RecoveryStatus.Completed, viewModel.recoveryStatus.value)
    }

    @Test
    fun `rekeyAccount creates new wrapper and updates status to Completed`() = runTest {
        val newPhrase = "one two three four five six seven eight nine ten eleven twelve"
        every { databaseManager.isRecoverySetupForUser(any()) } returns flowOf(true)
        coEvery { backupManager.getRecoveryPhrase(any()) } returns null
        coEvery { databaseManager.createRecoveryWrapper(any(), newPhrase) } returns Result.success(Unit)

        val viewModel = MnemonicRevealViewModel(
            backupManager,
            databaseManager,
            onboardingManager,
            diagnosticLogger,
            authRepository
        )
        testScheduler.runCurrent()

        var rekeyed = false
        viewModel.rekeyAccount(newPhrase) { rekeyed = true }
        testScheduler.runCurrent()

        assertTrue(rekeyed)
        coVerify { databaseManager.createRecoveryWrapper("user_123", newPhrase) }
        coVerify { backupManager.saveMnemonic("user_123", newPhrase) }
        coVerify { onboardingManager.setMnemonicSaved("user_123", true) }
        assertEquals(RecoveryStatus.Completed, viewModel.recoveryStatus.value)
    }
}
