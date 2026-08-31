package com.saurabh.artifact.startup

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.FirebaseAppCheck
import com.saurabh.artifact.repository.MaintenanceRepository
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.util.EnvironmentProvider
import com.saurabh.artifact.util.RescueTracker
import com.saurabh.artifact.util.StartupTracer
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import androidx.work.WorkManager
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult

@OptIn(ExperimentalCoroutinesApi::class)
class StartupCoordinatorTest {

    private val context = mockk<Context>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val encryptionManager = mockk<DatabaseEncryptionManager>(relaxed = true)
    private val environmentProvider = mockk<EnvironmentProvider>(relaxed = true)
    private val cleanupManager = mockk<com.saurabh.artifact.audio.ArtifactCleanupManager>(relaxed = true)
    private val maintenanceRepository = mockk<MaintenanceRepository>(relaxed = true)
    private val userSessionManager = mockk<UserSessionManager>(relaxed = true)
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    
    private val firebaseAppCheck = mockk<FirebaseAppCheck>()
    private val googleApiAvailability = mockk<GoogleApiAvailability>()
    
    private lateinit var coordinator: StartupCoordinator
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L

        mockkStatic(FirebaseAppCheck::class)
        every { FirebaseAppCheck.getInstance() } returns firebaseAppCheck

        every { context.applicationContext } returns context

        mockkObject(RescueTracker.Companion)
        val mockRescueTracker = mockk<RescueTracker>(relaxed = true)
        every { RescueTracker.getInstance(any()) } returns mockRescueTracker
        every { mockRescueTracker.isRescueModeRequired() } returns false

        mockkObject(StartupTracer)
        every { StartupTracer.mark(any()) } just Runs

        mockkStatic(GoogleApiAvailability::class)
        every { GoogleApiAvailability.getInstance() } returns googleApiAvailability
        every { googleApiAvailability.isGooglePlayServicesAvailable(any()) } returns ConnectionResult.SUCCESS

        Dispatchers.setMain(testDispatcher)

        coordinator = StartupCoordinator(
            context,
            workManager,
            encryptionManager,
            environmentProvider,
            { cleanupManager },
            maintenanceRepository,
            userSessionManager,
            { logoutCoordinator }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `successful App Check attestation sets status to VERIFIED`() = runTest(testDispatcher) {
        val mockToken = mockk<AppCheckToken> {
            every { token } returns "valid-token"
        }
        every { firebaseAppCheck.getAppCheckToken(false) } returns Tasks.forResult(mockToken)
        
        coEvery { encryptionManager.preload() } returns com.saurabh.artifact.security.PreloadResult.Success
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null
        
        coordinator.start()
        advanceUntilIdle()
        
        assertEquals(SecurityStatus.VERIFIED, coordinator.securityStatus.value)
    }

    @Test
    fun `failed App Check attestation sets status to UNVERIFIED after retries`() = runTest(testDispatcher) {
        // Force failure for all attempts
        every { firebaseAppCheck.getAppCheckToken(false) } returns Tasks.forException(Exception("Attestation failed"))
        
        coEvery { encryptionManager.preload() } returns com.saurabh.artifact.security.PreloadResult.Success
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null
        
        coordinator.start()
        advanceUntilIdle()
        
        assertEquals(SecurityStatus.UNVERIFIED, coordinator.securityStatus.value)
    }
}
