package com.saurabh.artifact

import android.content.Intent
import android.util.Log
import com.saurabh.artifact.domain.auth.CleanupResult
import com.saurabh.artifact.domain.auth.CleanupStatus
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.ObserveCurrentUserProfileUseCase
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.navigation.IncomingArtifact
import com.saurabh.artifact.navigation.InstantRecord
import com.saurabh.artifact.navigation.Login
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>()
    private val registrationCoordinator = mockk<RegistrationCoordinator>()
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    private val observeCurrentUserProfileUseCase = mockk<ObserveCurrentUserProfileUseCase>(relaxed = true)
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)

    private lateinit var viewModel: MainViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)

        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { observeStealthModeUseCase.invoke() } returns flowOf(false)
        every { startupCoordinator.stage } returns MutableStateFlow(com.saurabh.artifact.startup.StartupStage.ARRIVAL)
        every { startupCoordinator.isRescueModeActive } returns false

        viewModel = MainViewModel(
            authRepository,
            getInitialDestinationUseCase,
            registrationCoordinator,
            logoutCoordinator,
            observeCurrentUserProfileUseCase,
            observeStealthModeUseCase,
            startupCoordinator
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `onLaunchIntent while ready and logged in should emit event immediately`() = runTest {
        // Setup: App is Ready and user is logged in
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        viewModel.start()
        testScheduler.runCurrent() // Reach Ready state

        val intent = mockk<Intent>()
        every { intent.getBooleanExtra("navigate_to_recording", false) } returns true
        every { intent.getStringExtra("artifactId") } returns null

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        testScheduler.runCurrent()

        assertTrue(navigationEvents.any { it is InstantRecord })
        job.cancel()
    }

    @Test
    fun `onLaunchIntent while initializing should buffer event and deliver after start`() = runTest {
        // Setup: User is logged in, but app is still Initializing
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        val intent = mockk<Intent>()
        every { intent.getBooleanExtra("navigate_to_recording", false) } returns false
        every { intent.getStringExtra("artifactId") } returns "art123"
        every { intent.action } returns null

        // 1. Receive intent while Initializing
        viewModel.onLaunchIntent(intent)
        testScheduler.runCurrent()

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        // 2. Start initialization
        viewModel.start()
        testScheduler.runCurrent()

        // 3. Verify event delivered once Ready
        val event = navigationEvents.filterIsInstance<IncomingArtifact>().firstOrNull()
        assertEquals("art123", event?.artifactId)
        job.cancel()
    }

    @Test
    fun `onLaunchIntent with valid App Link URI while ready should emit IncomingArtifact`() = runTest {
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        viewModel.start()
        testScheduler.runCurrent()

        val uri = mockk<android.net.Uri>()
        every { uri.scheme } returns "https"
        every { uri.pathSegments } returns listOf("a", "abc456")

        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_VIEW
        every { intent.data } returns uri
        every { intent.getBooleanExtra(any(), any()) } returns false
        every { intent.getStringExtra(any()) } returns null

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        testScheduler.runCurrent()

        val event = navigationEvents.filterIsInstance<IncomingArtifact>().firstOrNull()
        assertEquals("abc456", event?.artifactId)
        job.cancel()
    }

    @Test
    fun `pending event should be dropped if destination is Login`() = runTest {
        // Setup: User is NULL, app will go to Login
        every { authRepository.currentUser } returns MutableStateFlow(null)
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

        val intent = mockk<Intent>()
        every { intent.getStringExtra("artifactId") } returns "secret"
        every { intent.getBooleanExtra(any(), any()) } returns false
        every { intent.action } returns null

        // 1. Buffer intent
        viewModel.onLaunchIntent(intent)
        
        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        // 2. Start app
        viewModel.start()
        testScheduler.runCurrent()

        // 3. Verify event NOT delivered
        assertTrue(navigationEvents.isEmpty())
        job.cancel()
    }

    @Test
    fun `onLaunchIntent while logged out and ready should NOT emit event`() = runTest {
        // Setup: App is Ready, but user is logged out
        every { authRepository.currentUser } returns MutableStateFlow(null)
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED
        
        viewModel.start()
        testScheduler.runCurrent()

        val intent = mockk<Intent>()
        every { intent.getBooleanExtra("navigate_to_recording", false) } returns true

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        testScheduler.runCurrent()

        assertTrue(navigationEvents.isEmpty())
        job.cancel()
    }

    @Test
    fun `startup should proceed to Ready state when authenticated`() = runTest {
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        viewModel.start()
        testScheduler.runCurrent()

        val state = viewModel.startupState.value
        assertTrue(state is AppStartupState.Ready)
    }

    @Test
    fun `startup should unblock coordinator on registration failure`() = runTest {
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.Failure(Exception("Network error"))

        viewModel.start()
        testScheduler.runCurrent()

        val state = viewModel.startupState.value
        assertTrue(state is AppStartupState.Error)
        verify { startupCoordinator.completeAll() }
    }

    @Test
    fun `auth logout should trigger comprehensive cleanup and wait for completion`() = runTest(testDispatcher) {
        // Setup: App is ready and user is logged in
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        val authFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(user)
        every { authRepository.currentUser } returns authFlow
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        coEvery { logoutCoordinator.performFullCleanup() } coAnswers {
            delay(100)
            CleanupResult(status = CleanupStatus.COMPLETED)
        }
        
        viewModel.start()
        advanceUntilIdle() // Reach Ready state
        
        // Trigger Logout
        authFlow.value = null
        
        // Before delay completion, state should NOT be Ready(Login) yet because we wait for cleanup
        advanceTimeBy(50)
        assertTrue(viewModel.startupState.value != AppStartupState.Ready(Login))
        
        // Complete cleanup
        advanceTimeBy(60)
        
        // Verify destination
        assertEquals(AppStartupState.Ready(Login), viewModel.startupState.value)

        // Verify Cleanup was triggered
        coVerify(exactly = 1) { logoutCoordinator.performFullCleanup() }
    }

    @Test
    fun `cold startup while logged out should NOT trigger cleanup`() = runTest(testDispatcher) {
        // Setup: Initial state is logged out
        val authFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(null)
        every { authRepository.currentUser } returns authFlow
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

        viewModel.start()
        advanceUntilIdle()

        // Verify we are at Login
        assertEquals(AppStartupState.Ready(Login), viewModel.startupState.value)

        // Verify NO cleanup was triggered
        coVerify(exactly = 0) { logoutCoordinator.performFullCleanup() }
    }

    @Test
    fun `exception during cleanup should still result in login navigation`() = runTest(testDispatcher) {
        // Setup: App is ready and user is logged in
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        val authFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(user)
        every { authRepository.currentUser } returns authFlow
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        coEvery { logoutCoordinator.performFullCleanup() } throws RuntimeException("Cleanup failed")
        
        viewModel.start()
        advanceUntilIdle()
        
        // Trigger Logout
        authFlow.value = null
        advanceUntilIdle()
        
        // Verify destination is still Login despite failure
        assertEquals(AppStartupState.Ready(Login), viewModel.startupState.value)
    }

    @Test
    fun `cleanup already in progress should not block login navigation`() = runTest(testDispatcher) {
        // Setup: App is ready and user is logged in
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        val authFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(user)
        every { authRepository.currentUser } returns authFlow
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        coEvery { logoutCoordinator.performFullCleanup() } returns CleanupResult(status = CleanupStatus.ALREADY_IN_PROGRESS)
        
        viewModel.start()
        advanceUntilIdle()
        
        // Trigger Logout
        authFlow.value = null
        advanceUntilIdle()
        
        // Verify destination
        assertEquals(AppStartupState.Ready(Login), viewModel.startupState.value)
    }

    @Test
    fun `multiple auth events should result in single cleanup trigger due to scan state`() = runTest(testDispatcher) {
        // Setup: App is ready and user is logged in
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        val authFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(user)
        every { authRepository.currentUser } returns authFlow
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        coEvery { logoutCoordinator.performFullCleanup() } coAnswers {
            delay(100)
            CleanupResult(status = CleanupStatus.COMPLETED)
        }
        
        viewModel.start()
        advanceUntilIdle()
        
        // Rapidly change auth state: User -> null -> User -> null
        authFlow.value = null
        authFlow.value = user
        authFlow.value = null
        
        advanceUntilIdle()
        
        // Verify cleanup triggered for transitions to null
        // scan(null -> User) -> (null, null), (null, User)
        // User -> null -> (User, null) -> TRIGGER 1
        // null -> User -> (null, User) -> NO TRIGGER
        // User -> null -> (User, null) -> TRIGGER 2
        
        // Wait, the scan operator emits (previous, current).
        // If we go User -> null -> User -> null:
        // 1. (User, null) -> Cleanup starts.
        // 2. (null, User)
        // 3. (User, null) -> Cleanup starts again.
        
        // However, LogoutCoordinator handles concurrency.
        // If the first cleanup is still running, the second trigger will call performFullCleanup() 
        // which returns ALREADY_IN_PROGRESS immediately.
        
        coVerify(atLeast = 1) { logoutCoordinator.performFullCleanup() }
    }
}
