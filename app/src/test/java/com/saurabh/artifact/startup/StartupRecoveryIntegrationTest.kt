package com.saurabh.artifact.startup

import com.saurabh.artifact.AppStartupState
import com.saurabh.artifact.MainViewModel
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.security.PreloadResult
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import androidx.lifecycle.SavedStateHandle
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupRecoveryIntegrationTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>(relaxed = true)
    private val registrationCoordinator = mockk<RegistrationCoordinator>(relaxed = true)
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    private val sessionManager = mockk<UserSessionManager>(relaxed = true)
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var viewModel: MainViewModel

    private val preloadResultFlow = MutableStateFlow<PreloadResult?>(null)

    @Before
    fun setup() {
        every { startupCoordinator.preloadResult } returns preloadResultFlow
        every { startupCoordinator.stage } returns MutableStateFlow(StartupStage.ARRIVAL)
        every { startupCoordinator.terminalError } returns MutableStateFlow(null)
        every { observeStealthModeUseCase() } returns flowOf(false)
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { sessionManager.owningUid } returns flowOf(null)

        viewModel = MainViewModel(
            authRepository,
            getInitialDestinationUseCase,
            registrationCoordinator,
            logoutCoordinator,
            sessionManager,
            observeStealthModeUseCase,
            startupCoordinator,
            savedStateHandle,
            diagnosticLogger
        )
    }

    @Test
    fun `MainViewModel transitions to Recovery state when preload returns RecoveryRequired`() = runTest {
        // 1. Start ViewModel
        viewModel.start()
        
        // 2. Simulate CORE readiness
        coEvery { startupCoordinator.awaitComponent(StartupComponent.CORE) } returns Unit
        
        // 3. Emit RecoveryRequired
        preloadResultFlow.value = PreloadResult.RecoveryRequired
        
        runCurrent()
        
        assertEquals(AppStartupState.Recovery, viewModel.startupState.value)
    }

    @Test
    fun `retryStartup resets and restarts the sequence`() = runTest {
        viewModel.retryStartup()
        
        verify { startupCoordinator.reset() }
        coVerify { startupCoordinator.start() }
    }
}
