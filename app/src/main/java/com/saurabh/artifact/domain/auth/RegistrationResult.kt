package com.saurabh.artifact.domain.auth

sealed interface RegistrationResult {
    object SuccessExistingUser : RegistrationResult
    object SuccessNewUser : RegistrationResult
    data class Failure(val exception: Throwable) : RegistrationResult
}
