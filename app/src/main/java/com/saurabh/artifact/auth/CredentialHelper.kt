package com.saurabh.artifact.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.saurabh.artifact.R
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.ui.util.UiText
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialHelper @Inject constructor(
    private val credentialManager: CredentialManager
) {

    /**
     * Launches the Google Sign-In flow using Credential Manager.
     * 
     * @param context The context used to launch the UI.
     * @param serverClientId The Web Client ID from the Google Cloud Console.
     * @param nonce An optional nonce for security. If null, one will be generated.
     * @param filterByAuthorizedAccounts Whether to only show accounts the user has previously authorized.
     */
    suspend fun getGoogleCredential(
        context: Context,
        serverClientId: String,
        nonce: String? = null,
        filterByAuthorizedAccounts: Boolean = false
    ): CredentialResult {
        return try {
            val finalNonce = nonce ?: generateNonce()
            
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                .setAutoSelectEnabled(true)
                .setNonce(finalNonce)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(
                context = context,
                request = request
            )

            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                CredentialResult.Success(googleCredential.idToken)
            } else {
                CredentialResult.Failure(UiText.StringResource(R.string.generic_error))
            }
        } catch (e: GetCredentialCancellationException) {
            CredentialResult.Canceled
        } catch (e: NoCredentialException) {
            CredentialResult.Failure(UiText.StringResource(R.string.unauthenticated_presence))
        } catch (e: Exception) {
            CredentialResult.Failure(ErrorMessageMapper.map(e), e)
        }
    }

    private fun generateNonce(): String {
        val rawNonce = UUID.randomUUID().toString()
        val bytes = rawNonce.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}
