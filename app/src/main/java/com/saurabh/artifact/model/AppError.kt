package com.saurabh.artifact.model

/**
 * Represents a domain-specific error within the Artifact application.
 * Designed to separate user-facing messages from internal debugging details.
 */
@Suppress("unused")
sealed class AppError : Exception() {
    
    abstract val technicalMessage: String
    override val message: String? get() = technicalMessage

    object PermissionDenied : AppError() {
        override val technicalMessage: String = "Permission denied"
    }

    object Unauthenticated : AppError() {
        override val technicalMessage: String = "User is not authenticated"
    }

    data class UsernameTaken(
        val username: String,
        override val technicalMessage: String = "Username '$username' is already taken"
    ) : AppError()

    data class UserNotFound(
        val userId: String,
        override val technicalMessage: String = "User with ID '$userId' not found"
    ) : AppError()

    object NetworkFailure : AppError() {
        override val technicalMessage: String = "Network failure"
    }

    data class Unknown(
        val original: Throwable,
        override val technicalMessage: String = original.message ?: "An unknown error occurred"
    ) : AppError()

    @Suppress("unused")
    data class InvalidInput(
        val details: String,
        override val technicalMessage: String = "Invalid input: $details"
    ) : AppError()

    companion object {
        fun from(e: Throwable): AppError = when (e) {
            is com.google.firebase.firestore.FirebaseFirestoreException -> {
                when (e.code) {
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED -> PermissionDenied
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE -> NetworkFailure
                    else -> Unknown(e)
                }
            }
            is AppError -> e
            else -> Unknown(e)
        }
    }
}
