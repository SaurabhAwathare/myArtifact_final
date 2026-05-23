package com.saurabh.artifact.model

/**
 * Represents a domain-specific error within the Artifact application.
 * Designed to separate user-facing messages from internal debugging details.
 */
sealed class AppError : Exception() {
    
    abstract val technicalMessage: String
    override val message: String? get() = technicalMessage

    data class PermissionDenied(
        val original: Throwable,
        override val technicalMessage: String = "Firebase: Missing or insufficient permissions"
    ) : AppError()

    data class Unauthenticated(
        override val technicalMessage: String = "User is not authenticated with Firebase"
    ) : AppError()

    data class UsernameTaken(
        val username: String,
        override val technicalMessage: String = "The username '$username' is already reserved"
    ) : AppError()

    data class TransactionFailed(
        val reason: String,
        val original: Throwable? = null,
        override val technicalMessage: String = "Firestore Transaction aborted: $reason"
    ) : AppError()

    data class UserNotFound(
        val userId: String,
        override val technicalMessage: String = "User document $userId not found in Firestore"
    ) : AppError()

    data class MalformedData(
        val details: String,
        override val technicalMessage: String = "Data serialization/integrity error: $details"
    ) : AppError()

    data class NetworkFailure(
        val original: Throwable,
        override val technicalMessage: String = "Network connectivity or Firebase backend unreachable"
    ) : AppError()

    data class Unknown(
        val original: Throwable,
        override val technicalMessage: String = original.message ?: "An unexpected error occurred"
    ) : AppError()

    data class InvalidInput(
        val details: String,
        override val technicalMessage: String = "Invalid input: $details"
    ) : AppError()

    companion object {
        fun from(e: Throwable): AppError = when (e) {
            is com.google.firebase.firestore.FirebaseFirestoreException -> {
                when (e.code) {
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED -> PermissionDenied(e)
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE -> NetworkFailure(e)
                    else -> Unknown(e)
                }
            }
            is AppError -> e
            else -> Unknown(e)
        }
    }
}
