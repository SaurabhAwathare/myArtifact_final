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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupRecoveryIntegrationTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>(relaxed = true)
    private val registrationCoordinator = mockk<RegistrationCoordinator>(relaxed = true)
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    private val maintenanceRepository = mockk<com.saurabh.artifact.repository.MaintenanceRepository>(relaxed = true)
    private val sessionManager = mockk<UserSessionManager>(relaxed = true)
    private val userProfileManager = mockk<com.saurabh.artifact.repository.UserProfileManager>(relaxed = true)
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var viewModel: MainViewModel

    private val preloadResultFlow = MutableStateFlow<PreloadResult?>(null)
    private val readyComponents = MutableStateFlow<Set<StartupComponent>>(emptySet())

    @Before
    fun setup() {
        every { startupCoordinator.preloadResult } returns preloadResultFlow
        every { startupCoordinator.stage } returns MutableStateFlow(StartupStage.ARRIVAL)
        every { startupCoordinator.terminalError } returns MutableStateFlow(null)
        every { observeStealthModeUseCase() } returns flowOf(false)
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { sessionManager.owningUid } returns flowOf(null)

        // REALISTIC DEPENDENCY MODEL: awaitComponent now actually suspends until readyComponents is updated
        coEvery { startupCoordinator.awaitComponent(any()) } coAnswers {
            val component = it.invocation.args[0] as StartupComponent
            readyComponents.first { set -> set.contains(component) }
        }

        viewModel = MainViewModel(
            authRepository,
            getInitialDestinationUseCase,
            registrationCoordinator,
            logoutCoordinator,
            maintenanceRepository,
            sessionManager,
            userProfileManager,
            mockk(relaxed = true), // userRepository
            observeStealthModeUseCase,
            startupCoordinator,
            savedStateHandle,
            diagnosticLogger
        )
    }

    @Test
    fun `MainViewModel transitions to Recovery state and keeps DATABASE locked when recovery is required`() = runTest {
        // 1. Start ViewModel
        viewModel.start()
        runCurrent()
        
        // ASSERT: ViewModel is suspended waiting for CORE
        assertEquals(AppStartupState.Initializing, viewModel.startupState.value)
        
        // 2. Simulate Coordinator emitting RecoveryRequired (Internal Step 1)
        preloadResultFlow.value = PreloadResult.RecoveryRequired
        runCurrent()
        
        // ASSERT: Still initializing because CORE hasn't been emitted yet (the deadlock point)
        assertEquals(AppStartupState.Initializing, viewModel.startupState.value)
        
        // 3. Simulate Coordinator emitting CORE (Internal Step 2 - The Fix)
        readyComponents.value = setOf(StartupComponent.CORE)
        runCurrent()
        
        // ASSERT: ViewModel is now unblocked and evaluates the preload result
        assertEquals(AppStartupState.Recovery, viewModel.startupState.value)
        
        // 4. VERIFY LOCK: DATABASE must NOT be emitted in this path
        assertFalse("DATABASE must remain locked during recovery", readyComponents.value.contains(StartupComponent.DATABASE))
    }

    @Test
    fun `retryStartup resets and restarts the sequence`() = runTest {
        viewModel.retryStartup()
        
        verify { startupCoordinator.reset() }
        coVerify { startupCoordinator.start() }
    }
}
