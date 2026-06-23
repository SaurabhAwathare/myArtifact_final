package com.saurabh.artifact.domain.auth

import android.util.Log
import com.saurabh.artifact.repository.UserRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegistrationCoordinator @Inject constructor(
    private val profileHealthChecker: ProfileHealthChecker,
    private val userRepository: UserRepository
) {
    private val mutex = Mutex()

    /**
     * Ensures that the authenticated user has a valid Firestore profile.
     * Repairs it if it's missing or corrupted.
     * 
     * @return [RegistrationResult] indicating the outcome.
     */
    suspend fun ensureProfileExists(): RegistrationResult = mutex.withLock {
        Log.i("APP_FLOW", "PROFILE_CREATE_STARTED")
        
        return try {
            when (val status = profileHealthChecker.checkHealth()) {
                HealthStatus.Healthy -> {
                    Log.i("APP_FLOW", "REGISTRATION_EXISTING_USER")
                    RegistrationResult.SuccessExistingUser
                }
                HealthStatus.RepairRequired, is HealthStatus.Corrupted, HealthStatus.Missing -> {
                    if (status == HealthStatus.Missing) {
                        Log.i("APP_FLOW", "PROFILE_CREATE_STARTED") // Explicitly for new profile case
                    } else {
                        Log.i("APP_FLOW", "PROFILE_REPAIR_STARTED: $status")
                    }

                    userRepository.getOrCreateProfile()
                        .fold(
                            onSuccess = { profileResult ->
                                if (profileResult.isNewUser) {
                                    Log.i("APP_FLOW", "REGISTRATION_NEW_USER")
                                    RegistrationResult.SuccessNewUser
                                } else {
                                    if (status != HealthStatus.Missing) {
                                        Log.i("APP_FLOW", "PROFILE_REPAIR_COMPLETED")
                                    }
                                    Log.i("APP_FLOW", "REGISTRATION_EXISTING_USER")
                                    RegistrationResult.SuccessExistingUser
                                }
                            },
                            onFailure = { e ->
                                Log.e("APP_FLOW", "REGISTRATION_FAILURE", e)
                                RegistrationResult.Failure(e)
                            }
                        )
                }
                HealthStatus.Unrecoverable -> {
                    Log.e("APP_FLOW", "REGISTRATION_FAILURE: Unrecoverable")
                    RegistrationResult.Failure(Exception("Profile is unrecoverable"))
                }
            }
        } catch (e: Exception) {
            Log.e("APP_FLOW", "REGISTRATION_FAILURE", e)
            RegistrationResult.Failure(e)
        }
    }
}
