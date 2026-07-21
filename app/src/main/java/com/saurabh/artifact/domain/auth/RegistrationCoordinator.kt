package com.saurabh.artifact.domain.auth

import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
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
        ArtifactLogger.i(DiagnosticCategory.AUTH, "PROFILE_CREATE_STARTED")
        
        return try {
            when (val status = profileHealthChecker.checkHealth()) {
                HealthStatus.Healthy -> {
                    ArtifactLogger.i(DiagnosticCategory.AUTH, "REGISTRATION_EXISTING_USER")
                    RegistrationResult.SuccessExistingUser
                }
                HealthStatus.RepairRequired, is HealthStatus.Corrupted, HealthStatus.Missing -> {
                    if (status == HealthStatus.Missing) {
                        ArtifactLogger.i(DiagnosticCategory.AUTH, "PROFILE_CREATE_STARTED") // Explicitly for new profile case
                    } else {
                        ArtifactLogger.i(DiagnosticCategory.AUTH, "PROFILE_REPAIR_STARTED")
                    }

                    userRepository.getOrCreateProfile()
                        .fold(
                            onSuccess = { profileResult ->
                                if (profileResult.isNewUser) {
                                    ArtifactLogger.i(DiagnosticCategory.AUTH, "REGISTRATION_NEW_USER")
                                    RegistrationResult.SuccessNewUser
                                } else {
                                    if (status is HealthStatus.RepairRequired || status is HealthStatus.Corrupted) {
                                        ArtifactLogger.i(DiagnosticCategory.AUTH, "PROFILE_REPAIR_COMPLETED")
                                    }
                                    ArtifactLogger.i(DiagnosticCategory.AUTH, "REGISTRATION_EXISTING_USER")
                                    RegistrationResult.SuccessExistingUser
                                }
                            },
                            onFailure = { e ->
                                ArtifactLogger.e(DiagnosticCategory.AUTH, "REGISTRATION_FAILURE", throwable = e)
                                RegistrationResult.Failure(e)
                            }
                        )
                }
                HealthStatus.Unrecoverable -> {
                    ArtifactLogger.e(DiagnosticCategory.AUTH, "REGISTRATION_FAILURE_UNRECOVERABLE")
                    RegistrationResult.Failure(Exception("Profile is unrecoverable"))
                }
            }
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.AUTH, "REGISTRATION_FAILURE", throwable = e)
            RegistrationResult.Failure(e)
        }
    }
}
