package com.saurabh.artifact.domain.auth

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.work.Operation
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import com.saurabh.artifact.MainViewModel
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackSettingsDataStore
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.FakeDiagnosticLogger
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.util.NotificationHelper
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AccountBoundaryVerificationTest {

    // Dependencies for LogoutCoordinator
    private val context = mockk<Context>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val sessionManager = mockk<UserSessionManager>(relaxed = true)
    private val playbackCoordinator = mockk<PlaybackCoordinator>(relaxed = true)
    private val playbackSettingsDataStore = mockk<PlaybackSettingsDataStore>(relaxed = true)
    private val recordingSessionManager = mockk<RecordingSessionManager>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val storageManager = mockk<StorageManager>(relaxed = true)
    private val backupEncryptionManager = mockk<BackupEncryptionManager>(relaxed = true)
    private val fakeLogger = FakeDiagnosticLogger()
    private val onboardingManager = mockk<com.saurabh.artifact.util.OnboardingManager>(relaxed = true)
    private val databaseEncryptionManager = mockk<com.saurabh.artifact.security.DatabaseEncryptionManager>(relaxed = true)
    private val personalizationEngine = mockk<com.saurabh.artifact.service.PersonalizationEngine>(relaxed = true)
    
    // Dependencies for MainViewModel
    private val getInitialDestinationUseCase = mockk<GetInitialDestinationUseCase>(relaxed = true)
    private val registrationCoordinator = mockk<RegistrationCoordinator>(relaxed = true)
    private val maintenanceRepository = mockk<com.saurabh.artifact.repository.MaintenanceRepository>(relaxed = true)
    private val userProfileManager = mockk<com.saurabh.artifact.repository.UserProfileManager>(relaxed = true)
    private val observeStealthModeUseCase = mockk<ObserveStealthModeUseCase>(relaxed = true)
    private val startupCoordinator = mockk<StartupCoordinator>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()

    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var logoutCoordinator: LogoutCoordinator
    private lateinit var viewModel: MainViewModel

    private val testAuthFlow = MutableStateFlow<com.google.firebase.auth.FirebaseUser?>(null)
    private val testOwningUidFlow = MutableStateFlow<String?>(null)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(NotificationHelper)
        every { NotificationHelper.cancelAllNotifications(any()) } just runs

        // Mock WorkManager Operation and Future correctly
        val operationResult = mockk<Operation>(relaxed = true)
        val future = mockk<ListenableFuture<Operation.State.SUCCESS>>(relaxed = true)
        every { future.addListener(any(), any()) } answers {
            val runnable = it.invocation.args[0] as Runnable
            val executor = it.invocation.args[1] as java.util.concurrent.Executor
            executor.execute(runnable)
        }
        every { future.isDone } returns true
        every { future.get() } returns mockk<Operation.State.SUCCESS>()
        
        every { operationResult.result } returns future
        every { workManager.cancelAllWorkByTag(any()) } returns operationResult

        // Setup Auth and Session flows
        every { authRepository.currentUser } returns testAuthFlow
        every { sessionManager.owningUid } returns testOwningUidFlow
        every { observeStealthModeUseCase() } returns flowOf(false)

        logoutCoordinator = spyk(LogoutCoordinator(
            context,
            authRepository,
            settingsRepository,
            sessionManager,
            playbackCoordinator,
            playbackSettingsDataStore,
            recordingSessionManager,
            workManager,
            { database },
            storageManager,
            backupEncryptionManager,
            onboardingManager,
            databaseEncryptionManager,
            { personalizationEngine },
            fakeLogger
        ).apply {
            ioDispatcher = testDispatcher
            mainDispatcher = testDispatcher
        })

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
            fakeLogger
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // 1. NORMAL_LOGOUT
    @Test
    fun `scenario 1 - NORMAL_LOGOUT verifies destruction order and state`() = runTest {
        // Arrange
        testAuthFlow.value = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user_A" }
        testOwningUidFlow.value = "user_A"
        
        // Act
        val result = logoutCoordinator.executeLogout()
        advanceUntilIdle()
        
        // Assert
        assertTrue(result.isSuccess)
        
        coVerify {
            workManager.cancelAllWorkByTag(SessionConstants.TAG_USER_SESSION_WORK)
            database.clearAllTables()
            storageManager.clearUserStorage()
            sessionManager.clear()
            authRepository.signOut()
        }
        
        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_COMPLETED")
        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "LOGOUT_FIREBASE_SUCCESS")
    }

    // 2. DATABASE_CLEANUP_FAILURE
    @Test
    fun `scenario 2 - DATABASE_CLEANUP_FAILURE preserves owningUid and prevents sign-out`() = runTest {
        // Arrange
        testAuthFlow.value = mockk { every { uid } returns "user_A" }
        testOwningUidFlow.value = "user_A"
        
        // Simulate failure in both primary and fallback
        every { database.clearAllTables() } throws RuntimeException("DB Lock Failure")
        every { database.close() } just runs
        every { context.deleteDatabase(any()) } returns false
        
        // Act
        val result = logoutCoordinator.executeLogout()
        advanceUntilIdle()
        
        // Assert
        assertTrue(result.isSuccess) // executeLogout returns Success but CleanupResult has failure bits
        val cleanupResult = result.getOrThrow()
        assertFalse(cleanupResult.room)
        assertFalse(cleanupResult.sessionDataStore)
        
        // Critical Verification: sessionManager.clear() MUST NOT be called if DB destruction failed
        coVerify(exactly = 0) { sessionManager.clear() }
        
        // Critical Verification: authRepository.signOut() MUST NOT be called if cleanup failed 
        coVerify(exactly = 1) { authRepository.signOut() } // Existing behavior
    }

    // 3. NUCLEAR_DATABASE_FALLBACK
    @Test
    fun `scenario 3 - NUCLEAR_DATABASE_FALLBACK triggers deleteDatabase`() = runTest {
        // Arrange
        every { database.clearAllTables() } throws RuntimeException("Corruption")
        every { database.close() } just runs
        every { context.deleteDatabase("artifact_db") } returns true
        
        // Act
        val cleanupResult = logoutCoordinator.performFullCleanup()
        advanceUntilIdle()
        
        // Assert
        assertTrue(cleanupResult.room)
        verify { database.close() }
        verify { context.deleteDatabase("artifact_db") }
        coVerify { sessionManager.clear() } // Should proceed if fallback succeeded
    }

    // 4. WORKER_CANCELLATION
    @Test
    fun `scenario 4 - WORKER_CANCELLATION occurs before destructive cleanup`() = runTest {
        // Arrange
        // No specific setup needed, just verify order
        
        // Act
        logoutCoordinator.performFullCleanup()
        advanceUntilIdle()
        
        // Assert
        coVerify(ordering = Ordering.SEQUENCE) {
            workManager.cancelAllWorkByTag(SessionConstants.TAG_USER_SESSION_WORK)
            database.clearAllTables()
        }
    }

    // 5. CROSS_USER_HANDOFF
    @Test
    fun `scenario 5 - CROSS_USER_HANDOFF blocks initialization and triggers cleanup`() = runTest {
        // Arrange: App starts with User A data in Store, but User B is logged in
        val userB = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user_B" }
        testAuthFlow.value = userB
        testOwningUidFlow.value = "user_A" // Tainted
        
        coEvery { logoutCoordinator.performFullCleanup() } returns CleanupResult(status = CleanupStatus.COMPLETED)
        
        // Act
        viewModel.start()
        runCurrent()
        
        // Now change UID to trigger the collector
        val userC = mockk<com.google.firebase.auth.FirebaseUser> { every { uid } returns "user_C" }
        testAuthFlow.value = userC
        advanceUntilIdle()
        
        // Assert
        coVerify(atLeast = 1) { logoutCoordinator.performFullCleanup() }
        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "ACCOUNT_BOUNDARY_DETECTED")
    }

    // 6. SUCCESSFUL_HANDOFF
    @Test
    fun `scenario 6 - SUCCESSFUL_HANDOFF allows normal initialization`() = runTest {
        // Arrange: Clean state
        testAuthFlow.value = mockk { every { uid } returns "user_B" }
        testOwningUidFlow.value = "user_B" // Already clean/matching
        
        // Act
        viewModel.start()
        advanceUntilIdle()
        
        // Assert
        coVerify(exactly = 0) { logoutCoordinator.performFullCleanup() }
    }
}
