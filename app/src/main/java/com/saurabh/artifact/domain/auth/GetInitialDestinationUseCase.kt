package com.saurabh.artifact.domain.auth

import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.util.OnboardingManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject

enum class InitialDestination {
    ONBOARDING,
    HOME,
    LOGIN
}

class GetInitialDestinationUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val onboardingManager: OnboardingManager
) {
    suspend operator fun invoke(): InitialDestination {
        val firebaseUser = authRepository.currentUser.value
        val onboardingCompleted = onboardingManager.isOnboardingCompleted.first()

        return when {
            !onboardingCompleted -> InitialDestination.ONBOARDING
            firebaseUser != null -> InitialDestination.HOME
            else -> InitialDestination.LOGIN
        }
    }
}
