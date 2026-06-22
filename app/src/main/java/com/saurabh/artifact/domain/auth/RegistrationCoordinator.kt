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
     * @return true if the profile is ready (healthy or repaired), false otherwise.
     */
    suspend fun ensureProfileExists(): Boolean = mutex.withLock {
        Log.i("APP_FLOW", "REGISTRATION_COORDINATOR_BEGIN")
        
        return when (val status = profileHealthChecker.checkHealth()) {
            HealthStatus.Healthy -> {
                Log.i("APP_FLOW", "REGISTRATION_COORDINATOR_SUCCESS: Healthy")
                true
            }
            HealthStatus.RepairRequired, HealthStatus.Missing -> {
                Log.i("APP_FLOW", "REGISTRATION_COORDINATOR_REPAIR: $status")
                userRepository.getOrCreateProfile()
                    .fold(
                        onSuccess = {
                            Log.i("APP_FLOW", "REGISTRATION_COORDINATOR_SUCCESS: Repaired")
                            true
                        },
                        onFailure = { e ->
                            Log.e("APP_FLOW", "REGISTRATION_COORDINATOR_FAILED", e)
                            false
                        }
                    )
            }
        }
    }
}
