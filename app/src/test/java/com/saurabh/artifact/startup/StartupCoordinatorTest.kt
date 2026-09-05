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
import com.google.android.gms.security.ProviderInstaller

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

        mockkStatic(ProviderInstaller::class)
        every { ProviderInstaller.installIfNeededAsync(any(), any()) } answers {
            val listener = secondArg<ProviderInstaller.ProviderInstallListener>()
            listener.onProviderInstalled()
        }

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
    fun `App Check readiness preserves pending status for lazy SDK retrieval`() = runTest(testDispatcher) {
        coEvery { encryptionManager.preload() } returns com.saurabh.artifact.security.PreloadResult.Success
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null
        
        coordinator.start()
        advanceUntilIdle()
        
        assertEquals(SecurityStatus.PENDING, coordinator.securityStatus.value)
    }

    @Test
    fun `global startup timeout emits terminal error when stable is not reached`() = runTest(testDispatcher) {
        coEvery { encryptionManager.preload() } returns com.saurabh.artifact.security.PreloadResult.Success
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null

        // Start coordinator
        coordinator.start()
        
        // We do NOT signal AUTH readiness, which should cause a timeout eventually
        // The global timeout is 20s. We advance time by 21 seconds.
        testScheduler.advanceTimeBy(21000)
        
        val error = coordinator.terminalError.value
        assert(error is StartupTimeoutException)
        assertEquals("Startup initialization timed out. Please check your connection and try again.", error?.message)
    }

    @Test
    fun `cold start follows full staggered delay sequence`() = runTest(testDispatcher) {
        coEvery { encryptionManager.preload() } returns com.saurabh.artifact.security.PreloadResult.Success
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null

        coordinator.start()
        
        // At t=0, stage is ARRIVAL
        assertEquals(StartupStage.ARRIVAL, coordinator.stage.value)
        
        // Core initialization happens (App Check + Security + Preload)
        // Then delay(200) -> PRESENCE
        testScheduler.advanceTimeBy(250) 
        assertEquals(StartupStage.PRESENCE, coordinator.stage.value)
        
        // Signal AUTH to move to DISCOVERY
        coordinator.emitReadiness(StartupComponent.AUTH)
        testScheduler.advanceTimeBy(250)
        assertEquals(StartupStage.DISCOVERY, coordinator.stage.value)
        
        // IMMERSION (300ms), RITUAL (500ms), STABLE (500ms)
        testScheduler.advanceTimeBy(1350)
        assertEquals(StartupStage.STABLE, coordinator.stage.value)
    }

    @Test
    fun `warm start accelerates to STABLE in 200ms`() = runTest(testDispatcher) {
        // 1. Initial Cold Start to reach STABLE
        coEvery { encryptionManager.preload() } returns com.saurabh.artifact.security.PreloadResult.Success
        coEvery { maintenanceRepository.getPendingDeletionUid() } returns null

        coordinator.start()
        coordinator.emitReadiness(StartupComponent.AUTH)
        advanceUntilIdle()
        assertEquals(StartupStage.STABLE, coordinator.stage.value)
        
        // 2. Trigger second start (Warm Start)
        val isStartedField = coordinator.javaClass.getDeclaredField("isStarted")
        isStartedField.isAccessible = true
        isStartedField.set(coordinator, false)

        coordinator.start()
        
        // Warm start sequence completes
        testScheduler.advanceTimeBy(500)
        assertEquals(StartupStage.STABLE, coordinator.stage.value)
    }
}
