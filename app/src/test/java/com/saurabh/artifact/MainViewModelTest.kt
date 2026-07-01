package com.saurabh.artifact

import android.content.Intent
import android.util.Log
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
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun `onLaunchIntent with recording shortcut while logged in should emit InstantRecord`() = runTest {
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)

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
    fun `onLaunchIntent with artifactId while logged in should emit IncomingArtifact`() = runTest {
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)

        val intent = mockk<Intent>()
        every { intent.getBooleanExtra("navigate_to_recording", false) } returns false
        every { intent.getStringExtra("artifactId") } returns "art123"
        every { intent.action } returns null

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        testScheduler.runCurrent()

        val event = navigationEvents.filterIsInstance<IncomingArtifact>().firstOrNull()
        assertEquals("art123", event?.artifactId)
        job.cancel()
    }

    @Test
    fun `onLaunchIntent with valid App Link URI should emit IncomingArtifact`() = runTest {
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)

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
    fun `onLaunchIntent with notification extra should take precedence over URI`() = runTest {
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)

        val uri = mockk<android.net.Uri>()
        every { uri.scheme } returns "https"
        every { uri.pathSegments } returns listOf("a", "uri_id")

        val intent = mockk<Intent>()
        every { intent.getStringExtra("artifactId") } returns "extra_id"
        every { intent.action } returns Intent.ACTION_VIEW
        every { intent.data } returns uri
        every { intent.getBooleanExtra(any(), any()) } returns false

        val navigationEvents = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { navigationEvents.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        testScheduler.runCurrent()

        val event = navigationEvents.filterIsInstance<IncomingArtifact>().firstOrNull()
        assertEquals("extra_id", event?.artifactId) // Extra takes precedence
        job.cancel()
    }

    @Test
    fun `onLaunchIntent with malformed URI should NOT emit event`() = runTest {
        val user = mockk<com.google.firebase.auth.FirebaseUser>()
        every { authRepository.currentUser } returns MutableStateFlow(user)

        val uri = mockk<android.net.Uri>()
        every { uri.scheme } returns "https"
        every { uri.pathSegments } returns listOf("wrong", "abc") // Wrong path segment

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

        assertTrue(navigationEvents.isEmpty())
        job.cancel()
    }

    @Test
    fun `onLaunchIntent while logged out should NOT emit any event`() = runTest {
        every { authRepository.currentUser } returns MutableStateFlow(null)

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
}
