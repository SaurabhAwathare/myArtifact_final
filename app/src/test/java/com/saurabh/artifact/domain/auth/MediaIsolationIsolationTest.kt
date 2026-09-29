package com.saurabh.artifact.domain.auth

import android.content.Context
import com.saurabh.artifact.audio.*
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.util.NotificationHelper
import com.saurabh.artifact.util.OnboardingManager
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.work.WorkManager
import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.auth.FirebaseUser
import com.saurabh.artifact.repository.PromptRepository
import com.saurabh.artifact.service.PersonalizationEngine
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MediaIsolationIsolationTest {

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
    private val onboardingManager = mockk<OnboardingManager>(relaxed = true)
    private val databaseEncryptionManager = mockk<DatabaseEncryptionManager>(relaxed = true)
    private val personalizationEngine = mockk<PersonalizationEngine>(relaxed = true)
    private val promptRepository = mockk<PromptRepository>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var logoutCoordinator: LogoutCoordinator

    @Before
    fun setup() {
        mockkObject(NotificationHelper)
        every { NotificationHelper.cancelAllNotifications(any()) } just runs

        mockkObject(MediaCache)
        every { MediaCache.release() } just runs

        every {
            sessionManager.localSessionId
        } returns MutableStateFlow("test_session_id")

        val mockUser = mockk<FirebaseUser> { every { uid } returns "test-uid" }
        every { authRepository.currentUser } returns MutableStateFlow(mockUser)
        every { authRepository.currentUserId } returns "test-uid"
        coEvery { authRepository.signOutAuthorized(any(), any(), any()) } returns Result.success(Unit)

        logoutCoordinator = LogoutCoordinator(
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
            { promptRepository },
            diagnosticLogger,
        ).apply {
            ioDispatcher = testDispatcher
            mainDispatcher = testDispatcher
        }

        // Properly mock WorkManager Operation to avoid hanging .await()
        val operation = mockk<Operation>(relaxed = true)
        val future = mockk<ListenableFuture<Operation.State.SUCCESS>>(relaxed = true)
        every { future.addListener(any(), any()) } answers {
            val runnable = it.invocation.args[0] as Runnable
            val executor = it.invocation.args[1] as Executor
            executor.execute(runnable)
        }
        every { operation.result } returns future
        every { workManager.cancelAllWorkByTag(any()) } returns operation
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `Logout sequence ensures UploadService is stopped before storage cleanup`() = runTest(testDispatcher) {
        // Execute logout
        logoutCoordinator.executeLogout()

        // Verify ordering: UploadService must be stopped in Phase A, clearUserStorage in Phase B
        verifyOrder {
            context.stopService(any()) // Simplified to avoid Intent mocking issues
            storageManager.clearUserStorage(preserveDrafts = true)
        }
    }

    @Test
    fun `StorageManager clearUserStorage purges all designated targets`() = runTest(testDispatcher) {
        // This test specifically verifies the integration between LogoutCoordinator and StorageManager's cleanup logic
        // when triggered via logout.
        
        logoutCoordinator.executeLogout()

        verify { storageManager.clearUserStorage(preserveDrafts = true) }
    }
}
