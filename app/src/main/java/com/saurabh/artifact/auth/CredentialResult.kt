package com.saurabh.artifact.auth

import com.saurabh.artifact.ui.util.UiText

/**
 * Sealed class representing the result of a Credential Manager request.
 */
sealed class CredentialResult {
    /**
     * Successfully obtained a credential.
     * @param idToken The ID token obtained from the credential.
     */
    data class Success(val idToken: String) : CredentialResult()

    /**
     * The user canceled the operation (e.g., swiped away the selector).
     */
    object Canceled : CredentialResult()

    /**
     * An error occurred during the process.
     * @param message A user-friendly error message.
     * @param throwable The underlying exception, if any.
     */
    data class Failure(val message: UiText, val throwable: Throwable? = null) : CredentialResult()
}
