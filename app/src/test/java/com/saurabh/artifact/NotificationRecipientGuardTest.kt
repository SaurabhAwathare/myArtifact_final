package com.saurabh.artifact

import android.content.Intent
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.model.PlaybackSource
import com.saurabh.artifact.navigation.Home
import com.saurabh.artifact.navigation.IncomingArtifact
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.startup.StartupCoordinator
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRecipientGuardTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>()
    private val registrationCoordinator = mockk<RegistrationCoordinator>()
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    private val sessionManager = mockk<com.saurabh.artifact.data.local.UserSessionManager>(relaxed = true)
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val diagnosticLogger = mockk<com.saurabh.artifact.diagnostics.DiagnosticLogger>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()

    private val testAuthFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(null)
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
        every { startupCoordinator.stage } returns MutableStateFlow(com.saurabh.artifact.startup.StartupStage.STABLE)
        every { startupCoordinator.preloadResult } returns MutableStateFlow(com.saurabh.artifact.security.PreloadResult.Success)
        
        coEvery { startupCoordinator.awaitComponent(any()) } returns Unit
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser

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

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `matching recipientId should allow notification navigation`() = runTest {
        val userId = "user-123"
        val artifactId = "art-456"
        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns userId }
        testAuthFlow.value = mockUser

        val intent = mockk<Intent> {
            every { getStringExtra("artifactId") } returns artifactId
            every { getStringExtra("recipientId") } returns userId
            every { getStringExtra("notificationType") } returns "RESONANCE"
            every { getBooleanExtra(any(), any()) } returns false
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

        assertTrue("Should navigate to artifact", events.any { it is IncomingArtifact && it.artifactId == artifactId })
        job.cancel()
    }

    @Test
    fun `mismatched recipientId should block notification navigation`() = runTest {
        val currentUserId = "user-correct"
        val recipientId = "user-wrong"
        val artifactId = "art-456"
        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns currentUserId }
        testAuthFlow.value = mockUser

        val intent = mockk<Intent> {
            every { getStringExtra("artifactId") } returns artifactId
            every { getStringExtra("recipientId") } returns recipientId
            every { getStringExtra("notificationType") } returns "RESONANCE"
            every { getBooleanExtra(any(), any()) } returns false
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

        assertTrue("Should NOT navigate to artifact", events.isEmpty())
        verify { diagnosticLogger.error(com.saurabh.artifact.diagnostics.DiagnosticCategory.AUTH, "NOTIFICATION_REJECTED_RECIPIENT_MISMATCH", any()) }
        job.cancel()
    }

    @Test
    fun `external deep link should NOT be blocked by recipient guard`() = runTest {
        val userId = "user-123"
        val artifactId = "art-shared"
        val mockUser = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns userId }
        testAuthFlow.value = mockUser

        // Simulate external https link
        val intent = mockk<Intent> {
            every { action } returns Intent.ACTION_VIEW
            every { data } returns android.net.Uri.parse("https://myartifact-555e3.web.app/a/$artifactId")
            every { getStringExtra(any()) } returns null
            every { getBooleanExtra(any(), any()) } returns false
        }

        val events = mutableListOf<Any>()
        val job = launch {
            viewModel.navigationEvent.collect { events.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        viewModel.start()
        advanceUntilIdle()

        assertTrue("Should navigate via DEEP_LINK", events.any { it is IncomingArtifact && it.artifactId == artifactId && it.source == PlaybackSource.DEEP_LINK })
        job.cancel()
    }
}
