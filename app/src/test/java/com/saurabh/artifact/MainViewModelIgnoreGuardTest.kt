package com.saurabh.artifact

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.navigation.Home
import com.saurabh.artifact.navigation.IncomingArtifact
import com.saurabh.artifact.navigation.Profile
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import com.saurabh.artifact.security.PreloadResult
import com.saurabh.artifact.startup.SecurityStatus
import com.saurabh.artifact.startup.StartupStage
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
class MainViewModelIgnoreGuardTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>()
    private val registrationCoordinator = mockk<RegistrationCoordinator>()
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    private val maintenanceRepository = mockk<com.saurabh.artifact.repository.MaintenanceRepository>(relaxed = true)
    private val sessionManager = mockk<com.saurabh.artifact.data.local.UserSessionManager>(relaxed = true)
    private val userProfileManager = mockk<com.saurabh.artifact.repository.UserProfileManager>(relaxed = true)
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val visibilityFilter = mockk<ArtifactVisibilityFilter>(relaxed = true)
    private val diagnosticLogger = mockk<com.saurabh.artifact.diagnostics.DiagnosticLogger>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()

    private val testAuthFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(null)
    private val ignoredUsersFlow = MutableStateFlow<Set<String>>(emptySet())
    private lateinit var viewModel: MainViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        Dispatchers.setMain(testDispatcher)

        testAuthFlow.value = null
        every { authRepository.currentUser } returns testAuthFlow
        every { authRepository.currentUserId } answers { testAuthFlow.value?.uid ?: "" }
        every { observeStealthModeUseCase.invoke() } returns flowOf(false)
        every { visibilityFilter.observeIgnoredUserIds(any()) } returns ignoredUsersFlow
        every {
            visibilityFilter.syncIgnoredUsersFromRemote(any(), any())
        } returns flowOf(Unit)
        every {
            visibilityFilter.syncReportsFromRemote(any(), any())
        } returns flowOf(Unit)
        
        every { sessionManager.owningUid } returns flowOf(null)
        every { sessionManager.isLoggingOut } returns flowOf(false)
        every { startupCoordinator.stage } returns MutableStateFlow(StartupStage.STABLE)
        every { startupCoordinator.preloadResult } returns MutableStateFlow(PreloadResult.Success)
        every { startupCoordinator.securityStatus } returns MutableStateFlow(SecurityStatus.PENDING)
        every { startupCoordinator.terminalError } returns MutableStateFlow(null)
        
        coEvery { startupCoordinator.awaitComponent(any()) } returns Unit
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null

        viewModel = MainViewModel(
            authRepository,
            getInitialDestinationUseCase,
            registrationCoordinator,
            logoutCoordinator,
            maintenanceRepository,
            sessionManager,
            userProfileManager,
            visibilityFilter,
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
    fun `navigation to profile should be blocked if user is ignored`() = runTest {
        val currentUserId = "user-1"
        val ignoredUserId = "user-ignored"
        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns currentUserId }
        testAuthFlow.value = mockUser
        ignoredUsersFlow.value = setOf(ignoredUserId)
        advanceUntilIdle()

        val intent = mockk<Intent>(relaxed = true) {
            every { getStringExtra("notificationType") } returns "FOLLOW"
            every { getStringExtra("userId") } returns ignoredUserId
            every { getStringExtra("artifactId") } returns null
            every { getStringExtra("recipientId") } returns null
            every { getBooleanExtra("navigate_to_recording", false) } returns false
            every { action } returns null
            every { data } returns null
        }

        val events = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { events.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        viewModel.start()
        advanceUntilIdle()

        assertTrue("Navigation should be empty as user is ignored", events.isEmpty())
        verify { diagnosticLogger.warn(com.saurabh.artifact.diagnostics.DiagnosticCategory.AUTH, "NAVIGATION_REJECTED_IGNORED_ACTOR", any()) }
        job.cancel()
    }

    @Test
    fun `navigation to artifact should be blocked if actor is ignored`() = runTest {
        val currentUserId = "user-1"
        val ignoredActorId = "user-ignored"
        val artifactId = "art-123"
        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns currentUserId }
        testAuthFlow.value = mockUser
        ignoredUsersFlow.value = setOf(ignoredActorId)
        advanceUntilIdle()

        val intent = mockk<Intent>(relaxed = true) {
            every { getStringExtra("notificationType") } returns null
            every { getStringExtra("artifactId") } returns artifactId
            every { getStringExtra("userId") } returns ignoredActorId
            every { getStringExtra("recipientId") } returns currentUserId
            every { getBooleanExtra("navigate_to_recording", false) } returns false
            every { action } returns null
            every { data } returns null
        }

        val events = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { events.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        viewModel.start()
        advanceUntilIdle()

        assertTrue("Navigation should be blocked", events.isEmpty())
        job.cancel()
    }

    @Test
    fun `legacy notifications with null actorId should be allowed`() = runTest {
        val currentUserId = "user-1"
        val artifactId = "art-legacy"
        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns currentUserId }
        testAuthFlow.value = mockUser
        advanceUntilIdle()
        
        // No actorId passed in intent (legacy simulation)
        val intent = mockk<Intent>(relaxed = true) {
            every { getStringExtra("notificationType") } returns null
            every { getStringExtra("artifactId") } returns artifactId
            every { getStringExtra("userId") } returns null 
            every { getStringExtra("recipientId") } returns currentUserId
            every { getBooleanExtra("navigate_to_recording", false) } returns false
            every { action } returns null
            every { data } returns null
        }

        val events = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { events.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        viewModel.start()
        advanceUntilIdle()

        assertTrue("Legacy navigation should be allowed", events.any { it is IncomingArtifact && it.artifactId == artifactId })
        job.cancel()
    }

    @Test
    fun `external deep links without actorId should be allowed`() = runTest {
        val artifactId = "art-external"
        testAuthFlow.value = mockk { every { uid } returns "me" }
        advanceUntilIdle()

        val mockUri = mockk<Uri>(relaxed = true) {
            every { scheme } returns "https"
            every { pathSegments } returns listOf("a", artifactId)
        }

        val intent = mockk<Intent>(relaxed = true) {
            every { action } returns Intent.ACTION_VIEW
            every { data } returns mockUri
            every { getStringExtra("notificationType") } returns null
            every { getStringExtra("artifactId") } returns null
            every { getStringExtra("userId") } returns null
            every { getStringExtra("recipientId") } returns null
            every { getBooleanExtra("navigate_to_recording", false) } returns false
        }

        val events = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { events.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        viewModel.start()
        advanceUntilIdle()

        assertTrue("External deep link should be allowed", events.any { it is IncomingArtifact && it.artifactId == artifactId })
        job.cancel()
    }

    @Test
    fun `unignoring an actor should allow subsequent navigation`() = runTest {
        val userId = "user-target"
        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "me" }
        testAuthFlow.value = mockUser
        
        ignoredUsersFlow.value = setOf(userId)
        advanceUntilIdle()

        val intent = mockk<Intent>(relaxed = true) {
            every { getStringExtra("notificationType") } returns "FOLLOW"
            every { getStringExtra("userId") } returns userId
            every { getStringExtra("artifactId") } returns null
            every { getStringExtra("recipientId") } returns null
            every { getBooleanExtra("navigate_to_recording", false) } returns false
            every { action } returns null
            every { data } returns null
        }

        val events = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { events.add(it) }
        }

        // Ensure startup is initiated so deferred navigation can reach Ready state
        viewModel.start()
        advanceUntilIdle()

        // 1. Try while ignored
        viewModel.onLaunchIntent(intent)
        advanceUntilIdle()
        assertTrue("Should be blocked", events.isEmpty())

        // 2. Unignore
        ignoredUsersFlow.value = emptySet()
        advanceUntilIdle()

        // 3. Try again
        viewModel.onLaunchIntent(intent)
        advanceUntilIdle()
        
        assertTrue("Should navigate after unignore", events.any { it is Profile && (it.userId == userId || it.personaId == userId) })
        job.cancel()
    }
}
