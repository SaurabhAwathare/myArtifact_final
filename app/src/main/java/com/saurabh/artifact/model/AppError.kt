package com.saurabh.artifact.model

/**
 * Represents a domain-specific error within the Artifact application.
 * Designed to separate user-facing messages from internal debugging details.
 */
sealed class AppError : Exception() {
    
    abstract val technicalMessage: String
    override val message: String? get() = technicalMessage

    /**
     * Defines a path for user recovery from this error.
     */
    sealed class RecoveryPath {
        object Retry : RecoveryPath()
        object Reauthenticate : RecoveryPath()
        object Support : RecoveryPath()
    }

    abstract val recoveryPath: RecoveryPath?

    data class PermissionDenied(
        override val technicalMessage: String = "Permission denied",
        override val recoveryPath: RecoveryPath? = RecoveryPath.Support,
    ) : AppError()

    data class Unauthenticated(
        override val technicalMessage: String = "User is not authenticated",
        override val recoveryPath: RecoveryPath? = RecoveryPath.Reauthenticate,
    ) : AppError()

    data class UsernameTaken(
        val username: String,
        override val technicalMessage: String = "Username '$username' is already taken",
        override val recoveryPath: RecoveryPath? = null,
    ) : AppError()

    data class UserNotFound(
        val userId: String,
        override val technicalMessage: String = "User with ID '$userId' not found",
        override val recoveryPath: RecoveryPath? = null,
    ) : AppError()

    data class NetworkFailure(
        override val technicalMessage: String = "Network failure",
        override val recoveryPath: RecoveryPath? = RecoveryPath.Retry,
    ) : AppError()

    data class NotFound(
        val type: String,
        val id: String,
        override val technicalMessage: String = "$type with ID '$id' not found",
        override val recoveryPath: RecoveryPath? = null,
    ) : AppError()

    data class Unknown(
        val original: Throwable,
        override val technicalMessage: String = original.message ?: "An unknown error occurred",
        override val recoveryPath: RecoveryPath? = RecoveryPath.Retry,
    ) : AppError()

    data class InvalidInput(
        val details: String,
        override val technicalMessage: String = "Invalid input: $details",
        override val recoveryPath: RecoveryPath? = null,
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
