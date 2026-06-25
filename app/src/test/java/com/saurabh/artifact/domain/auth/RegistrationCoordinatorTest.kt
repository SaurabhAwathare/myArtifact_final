package com.saurabh.artifact.domain.auth

import android.util.Log
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.repository.ProfileResult
import com.saurabh.artifact.model.User
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RegistrationCoordinatorTest {

    private val profileHealthChecker = mockk<ProfileHealthChecker>()
    private val userRepository = mockk<UserRepository>()
    private lateinit var coordinator: RegistrationCoordinator

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        
        coordinator = RegistrationCoordinator(profileHealthChecker, userRepository)
    }

    @Test
    fun `ensureProfileExists returns SuccessExistingUser when health is Healthy`() = runBlocking {
        coEvery { profileHealthChecker.checkHealth() } returns HealthStatus.Healthy

        val result = coordinator.ensureProfileExists()

        assertEquals(RegistrationResult.SuccessExistingUser, result)
        verify { Log.i("APP_FLOW", "REGISTRATION_EXISTING_USER") }
    }

    @Test
    fun `ensureProfileExists returns SuccessNewUser when health is Missing and creation succeeds for new user`() = runBlocking {
        coEvery { profileHealthChecker.checkHealth() } returns HealthStatus.Missing
        val user = mockk<User>()
        coEvery { userRepository.getOrCreateProfile() } returns Result.success(ProfileResult(user, isNewUser = true))

        val result = coordinator.ensureProfileExists()

        assertEquals(RegistrationResult.SuccessNewUser, result)
        verify { Log.i("APP_FLOW", "REGISTRATION_NEW_USER") }
    }

    @Test
    fun `ensureProfileExists returns SuccessExistingUser when health is Missing but creation returns existing user`() = runBlocking {
        coEvery { profileHealthChecker.checkHealth() } returns HealthStatus.Missing
        val user = mockk<User>()
        coEvery { userRepository.getOrCreateProfile() } returns Result.success(ProfileResult(user, isNewUser = false))

        val result = coordinator.ensureProfileExists()

        assertEquals(RegistrationResult.SuccessExistingUser, result)
        verify { Log.i("APP_FLOW", "REGISTRATION_EXISTING_USER") }
    }

    @Test
    fun `ensureProfileExists returns SuccessExistingUser when health is RepairRequired and repair succeeds`() = runBlocking {
        coEvery { profileHealthChecker.checkHealth() } returns HealthStatus.RepairRequired
        val user = mockk<User>()
        coEvery { userRepository.getOrCreateProfile() } returns Result.success(ProfileResult(user, isNewUser = false))

        val result = coordinator.ensureProfileExists()

        assertEquals(RegistrationResult.SuccessExistingUser, result)
        // Check for specific repair start log
        verify { Log.i("APP_FLOW", match { it.startsWith("PROFILE_REPAIR_STARTED") }) }
        verify { Log.i("APP_FLOW", "PROFILE_REPAIR_COMPLETED") }
    }

    @Test
    fun `ensureProfileExists returns Failure when health is Unrecoverable`() = runBlocking {
        coEvery { profileHealthChecker.checkHealth() } returns HealthStatus.Unrecoverable

        val result = coordinator.ensureProfileExists()

        assertTrue(result is RegistrationResult.Failure)
        assertEquals("Profile is unrecoverable", (result as RegistrationResult.Failure).exception.message)
    }

    @Test
    fun `ensureProfileExists returns Failure when userRepository fails`() = runBlocking {
        coEvery { profileHealthChecker.checkHealth() } returns HealthStatus.Missing
        val exception = Exception("Network error")
        coEvery { userRepository.getOrCreateProfile() } returns Result.failure(exception)

        val result = coordinator.ensureProfileExists()

        assertTrue(result is RegistrationResult.Failure)
        assertEquals(exception, (result as RegistrationResult.Failure).exception)
        verify { Log.e("APP_FLOW", "REGISTRATION_FAILURE", exception) }
    }
}
