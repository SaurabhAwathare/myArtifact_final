package com.saurabh.artifact

import androidx.lifecycle.SavedStateHandle
import android.util.Log
import com.saurabh.artifact.domain.auth.CleanupResult
import com.saurabh.artifact.domain.auth.CleanupStatus
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.UserProfileManager
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.navigation.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSafetySyncTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>(relaxed = true)
    private val registrationCoordinator = mockk<RegistrationCoordinator>(relaxed = true)
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    private val maintenanceRepository = mockk<com.saurabh.artifact.repository.MaintenanceRepository>(relaxed = true)
    private val sessionManager = mockk<com.saurabh.artifact.data.local.UserSessionManager>(relaxed = true)
    private val userProfileManager = mockk<UserProfileManager>(relaxed = true)
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()
    private val diagnosticLogger = mockk<com.saurabh.artifact.diagnostics.DiagnosticLogger>(relaxed = true)

    private val testAuthFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(null)
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        mockkObject(StartupMetrics)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)

        testAuthFlow.value = null
        every { authRepository.currentUser } returns testAuthFlow
        every { authRepository.currentUserId } answers { testAuthFlow.value?.uid ?: "" }
        every { observeStealthModeUseCase.invoke() } returns flowOf(false)
        every { sessionManager.owningUid } returns flowOf(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `Cold Start - successful authentication should trigger safety sync`() = runTest {
        // 1. App starts logged in
        val user = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user123" }
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val viewModel = createViewModel()
        viewModel.start()
        
        // 2. Verify safety sync initialized for user123
        verify(exactly = 1) { userProfileManager.initializeSafetySync("user123") }
    }

    @Test
    fun `Process Restoration - logged in user should trigger safety sync immediately`() = runTest {
        // 1. Setup SavedState as if startup completed for user_restored
        savedStateHandle["startup_completed"] = true
        savedStateHandle["resolved_destination_id"] = "HOME"
        savedStateHandle["resolved_uid"] = "user_restored"
        
        val user = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user_restored" }
        testAuthFlow.value = user

        // 2. App starts (Process Restoration)
        val viewModel = createViewModel()
        viewModel.start()
        
        // 3. Verify safety sync re-established even in restoration path
        verify(exactly = 1) { userProfileManager.initializeSafetySync("user_restored") }
    }

    @Test
    fun `Account Swap - should stop old sync and start new one after cleanup`() = runTest {
        // 1. App starts with User A
        val userA = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user_A" }
        testAuthFlow.value = userA
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        val viewModel = createViewModel()
        viewModel.start()
        
        verify(exactly = 1) { userProfileManager.initializeSafetySync("user_A") }

        // 2. Transition to User B (requires cleanup because A's data is still in DataStore)
        val userB = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user_B" }
        every { sessionManager.owningUid } returns flowOf("user_A")
        
        coEvery { logoutCoordinator.performFullCleanup() } coAnswers {
            CleanupResult(status = CleanupStatus.COMPLETED)
        }

        testAuthFlow.value = userB
        
        // 3. Verify Safety Sync Lifecycle
        verify(atLeast = 1) { userProfileManager.stopSafetySync() }
        verify(exactly = 1) { userProfileManager.initializeSafetySync("user_B") }
    }

    @Test
    fun `Logout - should stop safety sync`() = runTest {
        // 1. App starts logged in
        val user = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user123" }
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val viewModel = createViewModel()
        viewModel.start()
        
        verify(exactly = 1) { userProfileManager.initializeSafetySync("user123") }

        // 2. Logout
        testAuthFlow.value = null
        
        // 3. Verify safety sync stopped
        verify(atLeast = 1) { userProfileManager.stopSafetySync() }
    }

    @Test
    fun `Fresh Login - should trigger safety sync upon first authentication`() = runTest {
        // 1. App starts logged out
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED
        
        val viewModel = createViewModel()
        viewModel.start()
        
        verify(exactly = 0) { userProfileManager.initializeSafetySync(any()) }

        // 2. Simulate successful login
        val user = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "new_user" }
        testAuthFlow.value = user
        
        // 3. Verify safety sync starts immediately
        verify(exactly = 1) { userProfileManager.initializeSafetySync("new_user") }
    }

    private fun createViewModel() = MainViewModel(
        authRepository,
        getInitialDestinationUseCase,
        registrationCoordinator,
        logoutCoordinator,
        maintenanceRepository,
        sessionManager,
        userProfileManager,
        mockk(relaxed = true),
        observeStealthModeUseCase,
        startupCoordinator,
        savedStateHandle,
        diagnosticLogger
    )
}
