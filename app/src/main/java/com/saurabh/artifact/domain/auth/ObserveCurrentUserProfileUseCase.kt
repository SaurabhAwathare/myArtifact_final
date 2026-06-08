package com.saurabh.artifact.domain.auth

import com.saurabh.artifact.model.User
import com.saurabh.artifact.repository.AuthRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ObserveCurrentUserProfileUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    operator fun invoke(): StateFlow<User?> {
        return authRepository.userData
    }
}
