package com.saurabh.artifact.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.model.*
import com.saurabh.artifact.worker.IdentitySyncWorker
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import android.util.Log

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRecoveryTest {
    private val context = mockk<Context>(relaxed = true)
    private val sessionManager = mockk<UserSessionManager>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val visibilityFilter = mockk<com.saurabh.artifact.domain.ArtifactVisibilityFilter>(relaxed = true)
    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var userProfileManager: UserProfileManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        
        mockkObject(IdentitySyncWorker)
        every { IdentitySyncWorker.enqueue(any(), any(), any(), any()) } just Runs
        
        every { authRepository.userData } returns MutableStateFlow(null)

        userProfileManager = UserProfileManager(
            context = context,
            sessionManager = sessionManager,
            authRepository = authRepository,
            userRepository = userRepository,
            artifactRepository = artifactRepository,
            visibilityFilter = { visibilityFilter },
            managerScope = testScope.backgroundScope
        )
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `reconcileIdentitySync should NOT enqueue worker if versions match`() {
        val user = User(
            id = "user123",
            identityMetadata = IdentityMetadata(
                identityResetVersion = 5L,
                lastCompletedIdentityVersion = 5L
            )
        )

        userProfileManager.reconcileIdentitySync(user)

        verify(exactly = 0) {
            IdentitySyncWorker.enqueue(any(), any(), any(), any())
        }
    }

    @Test
    fun `reconcileIdentitySync should enqueue worker with KEEP if sync is pending`() {
        val user = User(
            id = "user123",
            identityMetadata = IdentityMetadata(
                identityResetVersion = 6L,
                lastCompletedIdentityVersion = 5L
            )
        )

        userProfileManager.reconcileIdentitySync(user)

        verify(exactly = 1) {
            IdentitySyncWorker.enqueue(
                context = context,
                userId = "user123",
                version = 6L,
                policy = ExistingWorkPolicy.KEEP
            )
        }
    }

    @Test
    fun `startIdentityMonitoring should trigger reconciliation on version change`() = testScope.runTest {
        val userDataFlow = MutableStateFlow<User?>(null)
        every { authRepository.userData } returns userDataFlow

        userProfileManager.startIdentityMonitoring()
        runCurrent()

        // 1. Initial Healthy State
        val userV5 = User(
            id = "u1", 
            identityMetadata = IdentityMetadata(
                identityResetVersion = 5L, 
                lastCompletedIdentityVersion = 5L
            )
        )
        userDataFlow.value = userV5
        runCurrent()
        verify(exactly = 0) { IdentitySyncWorker.enqueue(any(), any(), any(), any()) }

        // 2. Transition to Pending State
        val userV6Pending = User(
            id = "u1", 
            identityMetadata = IdentityMetadata(
                identityResetVersion = 6L, 
                lastCompletedIdentityVersion = 5L
            )
        )
        userDataFlow.value = userV6Pending
        runCurrent()
        
        verify(exactly = 1) {
            IdentitySyncWorker.enqueue(any(), "u1", 6L, ExistingWorkPolicy.KEEP)
        }
    }

    @Test
    fun `startIdentityMonitoring should ignore unrelated profile changes`() = testScope.runTest {
        val userDataFlow = MutableStateFlow<User?>(null)
        every { authRepository.userData } returns userDataFlow

        userProfileManager.startIdentityMonitoring()
        runCurrent()

        val user1 = User(
            id = "u1", 
            anonymousName = "Name1", 
            identityMetadata = IdentityMetadata(
                identityResetVersion = 5L, 
                lastCompletedIdentityVersion = 5L
            )
        )
        userDataFlow.value = user1
        runCurrent()

        val user2 = User(
            id = "u1", 
            anonymousName = "Name2", 
            identityMetadata = IdentityMetadata(
                identityResetVersion = 5L, 
                lastCompletedIdentityVersion = 5L
            )
        )
        userDataFlow.value = user2
        runCurrent()

        // Should not trigger because versions didn't change
        verify(exactly = 0) { IdentitySyncWorker.enqueue(any(), any(), any(), any()) }
    }
}
