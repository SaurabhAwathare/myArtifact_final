package com.saurabh.artifact.model

/**
 * Represents a domain-specific error within the Artifact application.
 * Designed to separate user-facing messages from internal debugging details.
 */
sealed class AppError : Exception() {
    
    abstract val technicalMessage: String
    override val message: String? get() = technicalMessage

    data class PermissionDenied(
        override val technicalMessage: String = "Permission denied",
    ) : AppError()

    data class Unauthenticated(
        override val technicalMessage: String = "User is not authenticated",
    ) : AppError()

    data class UsernameTaken(
        val username: String,
        override val technicalMessage: String = "Username '$username' is already taken",
    ) : AppError()

    data class UserNotFound(
        val userId: String,
        override val technicalMessage: String = "User with ID '$userId' not found",
    ) : AppError()

    data class NetworkFailure(
        override val technicalMessage: String = "Network failure",
    ) : AppError()

    data class NotFound(
        val type: String,
        val id: String,
        override val technicalMessage: String = "$type with ID '$id' not found",
    ) : AppError()

    data class Conflict(
        override val technicalMessage: String = "Operation conflict occurred",
    ) : AppError()

    data class Unknown(
        val original: Throwable,
        override val technicalMessage: String = original.message ?: "An unknown error occurred",
    ) : AppError()

    data class InvalidInput(
        val details: String,
        override val technicalMessage: String = "Invalid input: $details",
    ) : AppError()

    companion object {
        fun from(e: Throwable): AppError = when (e) {
            is com.google.firebase.firestore.FirebaseFirestoreException -> {
                when (e.code) {
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED -> PermissionDenied()
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE -> NetworkFailure()
                    else -> Unknown(e)
                }
            }
            is AppError -> e
            else -> Unknown(e)
        }
    }
}
