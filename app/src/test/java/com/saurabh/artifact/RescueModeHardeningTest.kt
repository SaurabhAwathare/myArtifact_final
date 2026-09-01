package com.saurabh.artifact

import androidx.lifecycle.SavedStateHandle
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.MaintenanceRepository
import com.saurabh.artifact.repository.UserProfileManager
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RescueModeHardeningTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>()
    private val registrationCoordinator = mockk<RegistrationCoordinator>()
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    private val maintenanceRepository = mockk<MaintenanceRepository>(relaxed = true)
    private val sessionManager = mockk<UserSessionManager>(relaxed = true)
    private val userProfileManager = mockk<UserProfileManager>(relaxed = true)
    private val visibilityFilter = mockk<ArtifactVisibilityFilter>(relaxed = true)
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>()
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var viewModel: MainViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { observeStealthModeUseCase() } returns flowOf(false)
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { authRepository.currentUserId } returns ""
        
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null
        every { sessionManager.owningUid } returns flowOf(null)
        
        viewModel = MainViewModel(
            authRepository, getInitialDestinationUseCase, registrationCoordinator,
            logoutCoordinator, maintenanceRepository, sessionManager,
            userProfileManager, visibilityFilter, observeStealthModeUseCase,
            startupCoordinator, savedStateHandle, diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Rescue Mode should still trigger cleanup if identity mismatch is detected`() = runTest {
        // Setup: App starts in Rescue Mode but there is dirty state from another user
        every { startupCoordinator.isRescueModeActive } returns true
        every { authRepository.currentUserId } returns "user-B"
        every { sessionManager.owningUid } returns flowOf("user-A") // Dirty state
        
        coEvery { logoutCoordinator.performFullCleanup() } returns mockk {
            every { isFullySuccessful } returns true
        }

        viewModel.start()
        advanceUntilIdle()

        // Verification: Boundary check MUST run and trigger cleanup
        coVerify(exactly = 1) { logoutCoordinator.performFullCleanup() }
        
        // Final state should be Rescue (cleanup succeeded)
        assertTrue(viewModel.startupState.value is AppStartupState.Rescue)
    }

    @Test
    fun `Rescue Mode should block if boundary cleanup fails`() = runTest {
        every { startupCoordinator.isRescueModeActive } returns true
        every { authRepository.currentUserId } returns "user-B"
        every { sessionManager.owningUid } returns flowOf("user-A")
        
        coEvery { logoutCoordinator.performFullCleanup() } returns mockk {
            every { isFullySuccessful } returns false // FAILED CLEANUP
        }

        viewModel.start()
        advanceUntilIdle()

        // Final state should be Error, NOT Rescue
        assertTrue(viewModel.startupState.value is AppStartupState.Error)
        assertEquals("Data maintenance required. Please restart the app.", (viewModel.startupState.value as AppStartupState.Error).message)
    }
}
