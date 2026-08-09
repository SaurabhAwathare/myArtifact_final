package com.saurabh.artifact

import androidx.lifecycle.SavedStateHandle
import android.content.Intent
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
import com.saurabh.artifact.diagnostics.FakeDiagnosticLogger
import com.saurabh.artifact.startup.StartupComponent
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.navigation.*
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
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val intent = mockk<Intent>()
    private val fakeLogger = FakeDiagnosticLogger()
    private val savedStateHandle = SavedStateHandle()

    private val testAuthFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(null)
    private lateinit var viewModel: MainViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        mockkObject(StartupMetrics)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)

        testAuthFlow.value = null
        every { authRepository.currentUser } returns testAuthFlow
        every { observeStealthModeUseCase.invoke() } returns flowOf(false)
        every { startupCoordinator.stage } returns MutableStateFlow(com.saurabh.artifact.startup.StartupStage.ARRIVAL)
        every { startupCoordinator.isRescueModeActive } returns false

        every { intent.getStringExtra("notificationType") } returns null
        every { intent.getStringExtra("artifactId") } returns null
        every { intent.getStringExtra("userId") } returns null
        every { intent.getBooleanExtra(any(), any()) } returns false
        every { intent.action } returns null
        every { intent.data } returns null

        coEvery {
            startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.CORE)
        } returns Unit

        viewModel = MainViewModel(
            authRepository,
            getInitialDestinationUseCase,
            registrationCoordinator,
            logoutCoordinator,
            observeStealthModeUseCase,
            startupCoordinator,
            savedStateHandle,
            fakeLogger
        )
    }

    @Test
    fun `startup should signal AUTH readiness on successful authenticated startup`() = runTest {
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        viewModel.start()
        testScheduler.runCurrent()

        verify { startupCoordinator.emitReadiness(StartupComponent.AUTH) }
        verify { StartupMetrics.onAuthReady() }
    }

    @Test
    fun `process restoration should signal AUTH readiness`() = runTest {
        // Setup SavedState for restoration
        savedStateHandle["startup_completed"] = true
        savedStateHandle["resolved_destination_id"] = "HOME"
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        testAuthFlow.value = user

        viewModel.start()
        testScheduler.runCurrent()

        verify { startupCoordinator.emitReadiness(StartupComponent.AUTH) }
        verify { StartupMetrics.onAuthReady() }
    }

    @Test
    fun `guest startup should signal AUTH readiness`() = runTest {
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

        viewModel.start()
        testScheduler.runCurrent()

        verify { startupCoordinator.emitReadiness(StartupComponent.AUTH) }
        verify { StartupMetrics.onAuthReady() }
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
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        viewModel.start()
        testScheduler.runCurrent() // Reach Ready state

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
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

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
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        viewModel.start()
        testScheduler.runCurrent()

        val uri = mockk<android.net.Uri>()
        every { uri.scheme } returns "https"
        every { uri.pathSegments } returns listOf("a", "abc456")

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
    fun `pending event should NOT be delivered while at Login`() = runTest {
        // Setup: User is NULL, app will go to Login
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

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
    fun `onLaunchIntent while logged out and ready should NOT emit event immediately`() = runTest {
        // Setup: App is Ready, but user is logged out
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED
        
        viewModel.start()
        testScheduler.runCurrent()

        every { intent.getBooleanExtra("navigate_to_recording", false) } returns true
        every { intent.getStringExtra("artifactId") } returns null
        every { intent.action } returns null

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
        testAuthFlow.value = user
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
        testAuthFlow.value = user
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
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        coEvery { logoutCoordinator.performFullCleanup() } coAnswers {
            delay(100)
            CleanupResult(status = CleanupStatus.COMPLETED)
        }
        
        viewModel.start()
        advanceUntilIdle() // Reach Ready state
        
        // Trigger Logout
        testAuthFlow.value = null
        
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
        testAuthFlow.value = null
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
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        coEvery { logoutCoordinator.performFullCleanup() } throws RuntimeException("Cleanup failed")
        
        viewModel.start()
        advanceUntilIdle()
        
        // Trigger Logout
        testAuthFlow.value = null
        advanceUntilIdle()
        
        // Verify destination is still Login despite failure
        assertEquals(AppStartupState.Ready(Login), viewModel.startupState.value)
    }

    @Test
    fun `cleanup already in progress should not block login navigation`() = runTest(testDispatcher) {
        // Setup: App is ready and user is logged in
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        coEvery { logoutCoordinator.performFullCleanup() } returns CleanupResult(status = CleanupStatus.ALREADY_IN_PROGRESS)
        
        viewModel.start()
        advanceUntilIdle()
        
        // Trigger Logout
        testAuthFlow.value = null
        advanceUntilIdle()
        
        // Verify destination
        assertEquals(AppStartupState.Ready(Login), viewModel.startupState.value)
    }

    @Test
    fun `pending event should survive authentication and deliver after profile check`() = runTest {
        // Setup: App will go to Login initially
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

        every { intent.getStringExtra("artifactId") } returns "deferred_123"
        every { intent.getBooleanExtra(any(), any()) } returns false
        every { intent.action } returns null

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        // 1. Buffer intent while logged out
        viewModel.onLaunchIntent(intent)
        
        // 2. Start app - goes to Login
        viewModel.start()
        testScheduler.runCurrent()
        
        assertEquals(AppStartupState.Ready(Login), viewModel.startupState.value)
        assertTrue(navigationEvents.isEmpty())

        // 3. Simulate Login
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        testAuthFlow.value = user
        viewModel.retryStartup() 
        
        testScheduler.runCurrent()

        // 4. Verify event delivered
        val event = navigationEvents.filterIsInstance<IncomingArtifact>().firstOrNull()
        assertEquals("deferred_123", event?.artifactId)
        job.cancel()
    }

    @Test
    fun `failed authentication should leave the event intact`() = runTest {
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

        every { intent.getStringExtra("artifactId") } returns "survivor"
        every { intent.getBooleanExtra(any(), any()) } returns false
        every { intent.action } returns null

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        // 1. Buffer intent
        viewModel.onLaunchIntent(intent)
        viewModel.start()
        testScheduler.runCurrent()

        // 2. Simulate Failed Authentication (Stays at Login)
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED
        viewModel.retryStartup()
        testScheduler.runCurrent()

        // 3. Verify event NOT consumed
        assertTrue(navigationEvents.isEmpty())

        // 4. Simulate Successful Authentication later
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        viewModel.retryStartup()
        testScheduler.runCurrent()

        // 5. Verify event delivered now
        val event = navigationEvents.filterIsInstance<IncomingArtifact>().firstOrNull()
        assertEquals("survivor", event?.artifactId)
        job.cancel()
    }

    @Test
    fun `warm start deferred link should deliver after login`() = runTest {
        // Setup: App is already Ready(Login)
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED
        
        viewModel.start()
        testScheduler.runCurrent()
        assertEquals(AppStartupState.Ready(Login), viewModel.startupState.value)

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        // 1. Receive intent while at Login (onNewIntent)
        every { intent.getStringExtra("artifactId") } returns "warm_123"
        every { intent.getBooleanExtra(any(), any()) } returns false
        every { intent.action } returns null

        viewModel.onLaunchIntent(intent)
        testScheduler.runCurrent()
        assertTrue(navigationEvents.isEmpty())

        // 2. Log in
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        testAuthFlow.value = user
        viewModel.retryStartup()
        testScheduler.runCurrent()

        // 3. Verify event delivered
        val event = navigationEvents.filterIsInstance<IncomingArtifact>().firstOrNull()
        assertEquals("warm_123", event?.artifactId)
        job.cancel()
    }

    @Test
    fun `duplicate navigation should be prevented on recomposition or auth changes`() = runTest {
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

        every { intent.getStringExtra("artifactId") } returns "once_only"
        every { intent.getBooleanExtra(any(), any()) } returns false
        every { intent.action } returns null

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        // 1. Buffer and Login
        viewModel.onLaunchIntent(intent)
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        viewModel.start()
        testScheduler.runCurrent()

        // 2. Verify delivered once
        assertEquals(1, navigationEvents.filterIsInstance<IncomingArtifact>().size)

        // 3. Simulate another Auth change or Ready state re-emission
        // Manually trigger deferred observer again (though it should be finished)
        viewModel.retryStartup()
        testScheduler.runCurrent()

        // 4. Verify NOT delivered again
        assertEquals(1, navigationEvents.filterIsInstance<IncomingArtifact>().size)
        job.cancel()
    }

    @Test
    fun `process death restoration should skip startup when already completed`() = runTest {
        // 1. Simulate previous successful startup
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        viewModel.start()
        testScheduler.runCurrent()
        
        // Verify state is Ready(Home) and SavedState is populated
        assertTrue(viewModel.startupState.value is AppStartupState.Ready)
        assertEquals(true, savedStateHandle.get<Boolean>("startup_completed"))
        assertEquals("HOME", savedStateHandle.get<String>("resolved_destination_id"))

        // 2. Simulate Process Death by creating a NEW ViewModel with SAME SavedStateHandle
        val newViewModel = MainViewModel(
            authRepository,
            getInitialDestinationUseCase,
            registrationCoordinator,
            logoutCoordinator,
            observeStealthModeUseCase,
            startupCoordinator,
            savedStateHandle,
            fakeLogger
        )

        // 3. Start the new ViewModel
        newViewModel.start()
        testScheduler.runCurrent()

        // 4. Verify startup logic was skipped and state restored immediately
        assertTrue(newViewModel.startupState.value is AppStartupState.Ready)
        assertEquals(Home, (newViewModel.startupState.value as AppStartupState.Ready).startDestination)
        
        // Verify executeStartup was NOT called (startupCoordinator.start() would have been called if it was)
        // Note: startupCoordinator was already called once during first ViewModel.start()
        verify(exactly = 1) { startupCoordinator.start() } 
    }

    @Test
    fun `process death restoration should NOT skip startup if user is logged out`() = runTest {
        // 1. Setup SavedState as if startup completed
        savedStateHandle["startup_completed"] = true
        savedStateHandle["resolved_destination_id"] = "HOME"
        
        // 2. Simulate Logged Out state
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

        // 3. Start ViewModel
        viewModel.start()
        testScheduler.runCurrent()

        // 4. Verify full startup executed (auth check occurred)
        coVerify { getInitialDestinationUseCase() }
        assertEquals(AppStartupState.Ready(Login), viewModel.startupState.value)
    }

    @Test
    fun `restoration with invalid destination ID should trigger fresh startup`() = runTest {
        // 1. Setup SavedState with INVALID ID
        savedStateHandle["startup_completed"] = true
        savedStateHandle["resolved_destination_id"] = "INVALID_ID"
        
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

        // 2. Start ViewModel
        viewModel.start()
        testScheduler.runCurrent()

        // 3. Verify full startup executed
        verify(exactly = 1) { startupCoordinator.start() }
        assertTrue(viewModel.startupState.value is AppStartupState.Ready)
    }

    @Test
    fun `pending startup event should survive process death`() = runTest {
        // 1. Buffer an event
        every { intent.getStringExtra("artifactId") } returns "survivor_123"
        every { intent.getBooleanExtra(any(), any()) } returns false
        every { intent.action } returns null

        viewModel.onLaunchIntent(intent)
        
        // Verify it was persisted to SavedState
        val persistedJson = savedStateHandle.get<String>("pending_event_json")
        assertTrue(persistedJson != null && persistedJson.contains("survivor_123"))

        // 2. Simulate Process Death
        val newViewModel = MainViewModel(
            authRepository,
            getInitialDestinationUseCase,
            registrationCoordinator,
            logoutCoordinator,
            observeStealthModeUseCase,
            startupCoordinator,
            savedStateHandle,
            fakeLogger
        )

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            newViewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        // 3. Restore startup state
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        testAuthFlow.value = user
        savedStateHandle["startup_completed"] = true
        savedStateHandle["resolved_destination_id"] = "HOME"
        
        newViewModel.start()
        testScheduler.runCurrent()

        // 4. Verify event delivered after restoration
        val event = navigationEvents.filterIsInstance<IncomingArtifact>().firstOrNull()
        assertEquals("survivor_123", event?.artifactId)
        
        // Verify SavedState cleared
        assertTrue(savedStateHandle.get<String>("pending_event_json") == null)
        job.cancel()
    }

    @Test
    fun `deferred observer should only run one instance`() = runTest {
        testAuthFlow.value = null
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.UNAUTHENTICATED

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        // 1. Send two intents while logged out
        every { intent.getStringExtra("artifactId") } returns "first"
        every { intent.getBooleanExtra(any(), any()) } returns false
        every { intent.action } returns null

        val intent2 = mockk<Intent>()
        every { intent2.getStringExtra("artifactId") } returns "second"
        every { intent2.getBooleanExtra(any(), any()) } returns false
        every { intent2.action } returns null

        viewModel.onLaunchIntent(intent)
        viewModel.onLaunchIntent(intent2) // Should replace the first and not double deliver

        // 2. Login
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        testAuthFlow.value = user
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        viewModel.start()
        testScheduler.runCurrent()

        // 3. Verify only one delivery (the last one)
        val artifacts = navigationEvents.filterIsInstance<IncomingArtifact>()
        assertEquals(1, artifacts.size)
        assertEquals("second", artifacts.first().artifactId)
        job.cancel()
    }
}
