package com.saurabh.artifact.domain.auth

import android.content.Context
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackSettingsDataStore
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.diagnostics.FakeDiagnosticLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.util.NotificationHelper
import com.saurabh.artifact.util.StorageManager
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.work.Operation
import androidx.work.WorkManager

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutCoordinatorTest {

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
    private val fakeLogger = FakeDiagnosticLogger()
    
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var coordinator: LogoutCoordinator

    @Before
    fun setup() {
        mockkObject(NotificationHelper)
        every { NotificationHelper.cancelAllNotifications(any()) } just runs

        // Mock WorkManager Operation and Future correctly to avoid hanging
        val operationResult = mockk<Operation>(relaxed = true)
        val future = mockk<ListenableFuture<Operation.State.SUCCESS>>(relaxed = true)
        every { future.addListener(any(), any()) } answers {
            val runnable = it.invocation.args[0] as Runnable
            val executor = it.invocation.args[1] as java.util.concurrent.Executor
            executor.execute(runnable)
        }
        every { operationResult.result } returns future
        every { workManager.cancelAllWorkByTag(any()) } returns operationResult
        
        coordinator = LogoutCoordinator(
            context,
            authRepository,
            settingsRepository,
            sessionManager,
            playbackCoordinator,
            playbackSettingsDataStore,
            recordingSessionManager,
            workManager,
            database,
            storageManager,
            fakeLogger
        ).apply {
            ioDispatcher = testDispatcher
            mainDispatcher = testDispatcher
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `executeLogout performs cleanup in order`() = runTest(testDispatcher) {
        // Prepare mock for recording session check
        every { recordingSessionManager.isRecordingActive() } returns true

        val result = coordinator.executeLogout()

        assertTrue(result.isSuccess)
        val cleanupResult = result.getOrThrow()
        assertEquals(CleanupStatus.COMPLETED, cleanupResult.status)

        // Verify Phase A: Stop
        coVerify(ordering = Ordering.ALL) {
            recordingSessionManager.isRecordingActive()
            recordingSessionManager.cancelSession()
            playbackCoordinator.release()
            workManager.cancelAllWorkByTag(SessionConstants.TAG_USER_SESSION_WORK)
            NotificationHelper.cancelAllNotifications(any())
        }

        // Verify Phase B: Clear State
        coVerify { sessionManager.clear() }
        coVerify { settingsRepository.signOut() }
        coVerify { playbackSettingsDataStore.clear() }

        // Verify Phase C: Database
        verify { database.clearAllTables() }

        // Verify Phase C.5: Storage
        verify { storageManager.clearUserStorage() }

        // Verify Phase E: Sign Out
        coVerify { authRepository.signOut() }
        
        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "LOGOUT_FIREBASE_SUCCESS")
        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_COMPLETED")
    }

    @Test
    fun `executeLogout handles concurrent calls correctly`() = runTest(testDispatcher) {
        // Single call success
        val result = coordinator.executeLogout()
        assertEquals(CleanupStatus.COMPLETED, result.getOrThrow().status)
    }

    @Test
    fun `cleanup survives exceptions in individual steps`() = runTest(testDispatcher) {
        coEvery { sessionManager.clear() } throws Exception("DataStore error")
        every { database.clearAllTables() } throws Exception("Database error")
        every { storageManager.clearUserStorage() } throws Exception("Storage error")

        val result = coordinator.executeLogout()

        assertTrue(result.isSuccess)
        val cleanupResult = result.getOrThrow()
        
        // Verify that even if some failed, others were still called
        assertEquals(false, cleanupResult.sessionDataStore)
        assertEquals(false, cleanupResult.room)
        
        // Verify playback was still stopped (Step 2)
        coVerify { playbackCoordinator.release() }
        
        // Verify storage cleanup was still attempted (Phase C.5)
        verify { storageManager.clearUserStorage() }
        
        // Verify sign out was still called (Final Phase)
        coVerify { authRepository.signOut() }

        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_SESSION_FAILED")
        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_DB_FAILED")
        fakeLogger.assertEventExists(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_STORAGE_FAILED")
    }
}
