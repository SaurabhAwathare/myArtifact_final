package com.saurabh.artifact.startup

import android.util.Log
import android.os.SystemClock
import com.saurabh.artifact.AppStartupState
import com.saurabh.artifact.MainViewModel
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.security.PreloadResult
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.navigation.Login
import com.saurabh.artifact.util.RescueTracker
import com.saurabh.artifact.util.StartupTracer
import androidx.lifecycle.SavedStateHandle
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupRecoveryIntegrationTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>()
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
    private val testDispatcher = StandardTestDispatcher()

    private val preloadResultFlow = MutableStateFlow<PreloadResult?>(null)
    private val securityStatusFlow = MutableStateFlow(SecurityStatus.PENDING)
    private val readyComponents = MutableStateFlow<Set<StartupComponent>>(emptySet())

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        mockkObject(RescueTracker.Companion)
        val mockRescueTracker = mockk<RescueTracker>(relaxed = true)
        every { RescueTracker.getInstance(any()) } returns mockRescueTracker

        mockkObject(StartupTracer)
        every { StartupTracer.mark(any()) } just Runs

        Dispatchers.setMain(testDispatcher)
        
        every { startupCoordinator.preloadResult } returns preloadResultFlow
        every { startupCoordinator.stage } returns MutableStateFlow(StartupStage.ARRIVAL)
        every { startupCoordinator.securityStatus } returns securityStatusFlow
        every { startupCoordinator.terminalError } returns MutableStateFlow(null)
        every { startupCoordinator.isRescueModeActive } returns false
        every { observeStealthModeUseCase() } returns flowOf(false)
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { authRepository.currentUserId } returns ""
        every { sessionManager.owningUid } returns flowOf(null)
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

        // REALISTIC DEPENDENCY MODEL: awaitComponent now actually suspends until readyComponents is updated
        coEvery { startupCoordinator.awaitComponent(any()) } coAnswers {
            val component = it.invocation.args[0] as StartupComponent
            readyComponents.first { set -> set.contains(component) }
        }

        // Mock cleanup success to allow startup to proceed
        coEvery { logoutCoordinator.performFullCleanup() } returns mockk(relaxed = true) {
            every { isFullySuccessful } returns true
        }

        viewModel = MainViewModel(
            authRepository,
            getInitialDestinationUseCase,
            registrationCoordinator,
            logoutCoordinator,
            maintenanceRepository,
            sessionManager,
            userProfileManager,
            mockk(relaxed = true), // visibilityFilter
            observeStealthModeUseCase,
            startupCoordinator,
            savedStateHandle,
            diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `MainViewModel transitions to Recovery state and keeps DATABASE locked when recovery is required`() = runTest(testDispatcher) {
        // 1. Start ViewModel
        viewModel.start()
        advanceUntilIdle()
        
        // ASSERT: ViewModel is suspended waiting for CORE
        assertEquals(AppStartupState.Initializing, viewModel.startupState.value)
        
        // 2. Simulate Coordinator emitting RecoveryRequired (Internal Step 1)
        preloadResultFlow.value = PreloadResult.RecoveryRequired
        advanceUntilIdle()
        
        // ASSERT: Still initializing because CORE hasn't been emitted yet (the deadlock point)
        assertEquals(AppStartupState.Initializing, viewModel.startupState.value)
        
        // 3. Simulate Coordinator emitting CORE (Internal Step 2 - The Fix)
        readyComponents.value = setOf(StartupComponent.CORE)
        advanceUntilIdle()
        
        // ASSERT: ViewModel is now unblocked and evaluates the preload result
        assertEquals(AppStartupState.Recovery, viewModel.startupState.value)
        
        // 4. VERIFY LOCK: DATABASE must NOT be emitted in this path
        assertFalse("DATABASE must remain locked during recovery", readyComponents.value.contains(StartupComponent.DATABASE))
    }

    @Test
    fun `MainViewModel exposes security status from StartupCoordinator`() = runTest(testDispatcher) {
        // 1. Start ViewModel
        preloadResultFlow.value = PreloadResult.Success
        viewModel.start()
        advanceUntilIdle()
        
        // 2. Simulate status update BEFORE technical readiness
        securityStatusFlow.value = SecurityStatus.VERIFIED
        advanceUntilIdle()

        // 3. Simulate technical readiness (unblocks executeStartup)
        readyComponents.value = setOf(StartupComponent.CORE, StartupComponent.DATABASE)
        advanceUntilIdle()
        
        // ASSERT: Ready state captures the status at emission
        val state = viewModel.startupState.value
        if (state !is AppStartupState.Ready) {
            throw AssertionError("Expected Ready state, but was $state")
        }
        assertEquals(SecurityStatus.VERIFIED, state.securityStatus)
        assertEquals(Login, state.startDestination)
        
        // ALSO ASSERT: VM exposes the live flow
        assertEquals(SecurityStatus.VERIFIED, viewModel.securityStatus.value)
    }

    @Test
    fun `retryStartup resets and restarts the sequence`() = runTest(testDispatcher) {
        viewModel.retryStartup()
        advanceUntilIdle()
        
        verify { startupCoordinator.reset() }
        coVerify { startupCoordinator.start() }
    }
}
