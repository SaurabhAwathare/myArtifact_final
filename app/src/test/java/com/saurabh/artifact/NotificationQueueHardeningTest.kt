package com.saurabh.artifact

import androidx.lifecycle.SavedStateHandle
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.model.PlaybackSource
import com.saurabh.artifact.navigation.IncomingArtifact
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.MaintenanceRepository
import com.saurabh.artifact.repository.UserProfileManager
import com.saurabh.artifact.startup.StartupCoordinator
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationQueueHardeningTest {

    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>()
    private val registrationCoordinator = mockk<RegistrationCoordinator>()
    private val logoutCoordinator = mockk<com.saurabh.artifact.domain.auth.LogoutCoordinator>(relaxed = true)
    private val maintenanceRepository = mockk<MaintenanceRepository>(relaxed = true)
    private val sessionManager = mockk<com.saurabh.artifact.data.local.UserSessionManager>(relaxed = true)
    private val userProfileManager = mockk<UserProfileManager>(relaxed = true)
    private val visibilityFilter = mockk<ArtifactVisibilityFilter>(relaxed = true)
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>()
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var viewModel: MainViewModel
    private val testDispatcher = StandardTestDispatcher()

    private val currentUserId = "user-123"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { observeStealthModeUseCase() } returns flowOf(false)
        every { authRepository.currentUser } returns MutableStateFlow(mockk {
            every { uid } returns currentUserId
        })
        every { authRepository.currentUserId } returns currentUserId
        
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null
        every { sessionManager.owningUid } returns flowOf(currentUserId)
        
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
    fun `multiple notifications during cold start should be delivered in FIFO order`() = runTest {
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        
        // 1. App starts initializing
        viewModel.start()
        
        // 2. Buffer two notifications
        viewModel.onLaunchIntent(mockk(relaxed = true) {
            every { getStringExtra("artifactId") } returns "art-1"
            every { getStringExtra("recipientId") } returns currentUserId
        })
        viewModel.onLaunchIntent(mockk(relaxed = true) {
            every { getStringExtra("artifactId") } returns "art-2"
            every { getStringExtra("recipientId") } returns currentUserId
        })

        // 3. Move app to Ready state
        advanceUntilIdle()
        
        val deliveredEvents = mutableListOf<Any>()
        val collectJob = launch {
            viewModel.navigationEvent.collect { deliveredEvents.add(it) }
        }
        
        advanceUntilIdle()
        
        assertEquals(2, deliveredEvents.size)
        assertEquals("art-1", (deliveredEvents[0] as IncomingArtifact).artifactId)
        assertEquals("art-2", (deliveredEvents[1] as IncomingArtifact).artifactId)
        
        collectJob.cancel()
    }

    @Test
    fun `queue should drop oldest event when MAX_CAPACITY is exceeded`() = runTest {
        // Capacity is 5. We'll send 6.
        val intents = (1..6).map { i -> 
            mockk<android.content.Intent>(relaxed = true) {
                every { getStringExtra("artifactId") } returns "art-$i"
                every { getStringExtra("recipientId") } returns currentUserId
            }
        }

        intents.forEach { viewModel.onLaunchIntent(it) }
        
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        viewModel.start()
        
        val deliveredEvents = mutableListOf<Any>()
        val collectJob = launch {
            viewModel.navigationEvent.collect { deliveredEvents.add(it) }
        }
        
        advanceUntilIdle()
        
        // Should deliver 5 events: art-2 to art-6 (art-1 dropped)
        assertEquals(5, deliveredEvents.size)
        assertEquals("art-2", (deliveredEvents[0] as IncomingArtifact).artifactId)
        assertEquals("art-6", (deliveredEvents[4] as IncomingArtifact).artifactId)
        
        collectJob.cancel()
    }

    @Test
    fun `notification without recipientId should be rejected (Fail Closed)`() = runTest {
        coEvery { getInitialDestinationUseCase() } returns InitialDestination.AUTHENTICATED
        coEvery { registrationCoordinator.ensureProfileExists() } returns RegistrationResult.SuccessExistingUser
        viewModel.start()
        advanceUntilIdle()
        
        val intent = mockk<android.content.Intent>(relaxed = true) {
            every { getStringExtra("artifactId") } returns "art-1"
            every { getStringExtra("recipientId") } returns null // Missing recipient
        }

        val deliveredEvents = mutableListOf<Any>()
        val collectJob = launch {
            viewModel.navigationEvent.collect { deliveredEvents.add(it) }
        }

        viewModel.onLaunchIntent(intent)
        advanceUntilIdle()

        // Should NOT be delivered
        assertEquals(0, deliveredEvents.size)
        verify { diagnosticLogger.error(any(), "NOTIFICATION_REJECTED_INVALID_RECIPIENT_WARM", any()) }
        
        collectJob.cancel()
    }
}
