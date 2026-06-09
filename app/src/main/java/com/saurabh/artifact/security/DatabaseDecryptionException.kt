package com.saurabh.artifact.security

/**
 * Exception thrown when the database exists but cannot be decrypted with the current security context.
 * This signals that a recovery flow (e.g., via mnemonic) might be necessary.
 */
class DatabaseDecryptionException(
    message: String,
    cause: Throwable? = null,
    val isRecoveryPossible: Boolean = true
) : Exception(message, cause)
