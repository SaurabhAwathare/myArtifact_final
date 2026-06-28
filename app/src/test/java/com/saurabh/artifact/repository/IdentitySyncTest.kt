package com.saurabh.artifact.repository

import android.content.Context
import androidx.work.WorkManager
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.*
import com.saurabh.artifact.worker.IdentitySyncWorker
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import android.util.Log
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdentitySyncTest {
    private val context = mockk<Context>(relaxed = true)
    private val sessionManager = mockk<UserSessionManager>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private val testScope = kotlinx.coroutines.test.TestScope(testDispatcher)

    private lateinit var userProfileManager: UserProfileManager

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } answers { println("${args[0]}: ${args[1]}"); 0 }
        every { Log.i(any<String>(), any<String>()) } answers { println("${args[0]}: ${args[1]}"); 0 }
        every { Log.e(any<String>(), any<String>()) } answers { println("${args[0]}: ${args[1]}"); 0 }
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } answers { println("${args[0]}: ${args[1]}"); 0 }

        every { authRepository.userData } returns kotlinx.coroutines.flow.MutableStateFlow(null)

        mockkObject(IdentitySyncWorker)
        every { IdentitySyncWorker.enqueue(any(), any()) } just Runs
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `updateUsername should trigger local snapshot update and enqueue worker`() = testScope.runTest {
        userProfileManager = UserProfileManager(
            context = context,
            sessionManager = sessionManager,
            authRepository = authRepository,
            userRepository = userRepository,
            artifactRepository = artifactRepository,
            managerScope = testScope.backgroundScope
        )

        val userId = "user123"
        val newUsername = "NewName"
        val profile = UserProfile(
            anonymousId = "anon1",
            username = "OldName",
            sigil = "sigil1",
            avatarSeed = "seed1",
            avatarColor = "color1",
            avatarConfig = AvatarConfig(seed = "seed1")
        )

        val profileFlow = kotlinx.coroutines.flow.MutableStateFlow(profile)

        every { authRepository.currentUserId } returns userId
        every { sessionManager.userProfile } returns profileFlow
        coEvery { userRepository.createUsername(userId, newUsername) } returns Result.success(Unit)

        userProfileManager.updateUsername(newUsername)
        
        // Wait for the async launch in updateUsername
        runCurrent()

        coVerify {
            artifactRepository.updateLocalAuthorSnapshot(userId, match {
                it.name == newUsername && it.anonymousId == "anon1"
            })
        }
        verify {
            IdentitySyncWorker.enqueue(context, userId)
        }
    }

    @Test
    fun `updateAvatarConfig should trigger local snapshot update and enqueue worker`() = testScope.runTest {
        userProfileManager = UserProfileManager(
            context = context,
            sessionManager = sessionManager,
            authRepository = authRepository,
            userRepository = userRepository,
            artifactRepository = artifactRepository,
            managerScope = testScope.backgroundScope
        )

        val userId = "user123"
        val newConfig = AvatarConfig(seed = "newSeed", theme = "CARTOON")
        val profile = UserProfile(
            anonymousId = "anon1",
            username = "UserName",
            sigil = "sigil1",
            avatarSeed = "oldSeed",
            avatarColor = "color1",
            avatarConfig = AvatarConfig(seed = "oldSeed")
        )

        val profileFlow = kotlinx.coroutines.flow.MutableStateFlow(profile)

        every { authRepository.currentUserId } returns userId
        every { sessionManager.userProfile } returns profileFlow
        coEvery { userRepository.updateAvatarConfig(userId, newConfig) } returns Result.success(Unit)

        userProfileManager.updateAvatarConfig(newConfig)
        
        // Wait for the async launch
        runCurrent()

        coVerify {
            artifactRepository.updateLocalAuthorSnapshot(userId, match {
                it.avatarConfig == newConfig && it.avatarSeed == "newSeed"
            })
        }
        verify {
            IdentitySyncWorker.enqueue(context, userId)
        }
    }
}
