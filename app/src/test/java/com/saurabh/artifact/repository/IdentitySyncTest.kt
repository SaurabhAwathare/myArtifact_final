package com.saurabh.artifact.repository

import android.content.Context
import androidx.work.WorkManager
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.model.*
import com.saurabh.artifact.worker.IdentitySyncWorker
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class IdentitySyncTest {
    private val context = mockk<Context>(relaxed = true)
    private val sessionManager = mockk<UserSessionManager>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val artifactRepository = mockk<ArtifactRepository>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)

    private lateinit var userProfileManager: UserProfileManager

    @Before
    fun setup() {
        mockkObject(IdentitySyncWorker)
        every { IdentitySyncWorker.enqueue(any(), any()) } just Runs

        userProfileManager = UserProfileManager(
            context = context,
            sessionManager = sessionManager,
            authRepository = authRepository,
            userRepository = userRepository,
            artifactRepository = artifactRepository
        )
    }

    @Test
    fun `updateUsername should trigger local snapshot update and enqueue worker`() = runBlocking {
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

        every { authRepository.currentUserId } returns userId
        every { sessionManager.userProfile } returns flowOf(profile)
        coEvery { userRepository.createUsername(userId, newUsername) } returns Result.success(Unit)

        userProfileManager.updateUsername(newUsername)

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
    fun `updateAvatarConfig should trigger local snapshot update and enqueue worker`() = runBlocking {
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

        every { authRepository.currentUserId } returns userId
        every { sessionManager.userProfile } returns flowOf(profile)
        coEvery { userRepository.updateAvatarConfig(userId, newConfig) } returns Result.success(Unit)

        userProfileManager.updateAvatarConfig(newConfig)

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
