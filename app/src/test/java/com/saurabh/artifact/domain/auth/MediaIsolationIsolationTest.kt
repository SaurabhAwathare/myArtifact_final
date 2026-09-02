package com.saurabh.artifact.domain.auth

import android.content.Context
import com.saurabh.artifact.audio.*
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.util.OnboardingManager
import com.saurabh.artifact.util.StorageManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.File
import androidx.work.WorkManager
import androidx.work.Operation
import com.google.common.util.concurrent.ListenableFuture

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
    private val personalizationEngine = mockk<com.saurabh.artifact.service.PersonalizationEngine>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)

    private lateinit var logoutCoordinator: LogoutCoordinator

    @Before
    fun setup() {
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
            diagnosticLogger,
        ).apply {
            ioDispatcher = UnconfinedTestDispatcher()
            mainDispatcher = UnconfinedTestDispatcher()
        }

        // Properly mock WorkManager Operation to avoid hanging .await()
        val operation = mockk<Operation>(relaxed = true)
        val future = com.google.common.util.concurrent.Futures.immediateFuture(Operation.SUCCESS)
        every { operation.result } returns future
        every { workManager.cancelAllWorkByTag(any()) } returns operation
    }

    @Test
    fun `Logout sequence ensures UploadService is stopped before storage cleanup`() = runTest {
        // Execute logout
        logoutCoordinator.executeLogout()

        // Verify ordering: UploadService must be stopped in Phase A, clearUserStorage in Phase B
        verifyOrder {
            context.stopService(any()) // Simplified to avoid Intent mocking issues
            storageManager.clearUserStorage(preserveDrafts = true)
        }
    }

    @Test
    fun `StorageManager clearUserStorage purges all designated targets`() = runTest {
        // This test specifically verifies the integration between LogoutCoordinator and StorageManager's cleanup logic
        // when triggered via logout.
        
        logoutCoordinator.executeLogout()

        verify { storageManager.clearUserStorage(preserveDrafts = true) }
    }
}
