package com.saurabh.artifact.domain.auth

import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.saurabh.artifact.model.User
import com.saurabh.artifact.model.UserPrivateSettings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed class HealthStatus {
    object Healthy : HealthStatus()
    data class Corrupted(val reasons: List<String>) : HealthStatus()
    object RepairRequired : HealthStatus()
    object Unrecoverable : HealthStatus()
    object Missing : HealthStatus()
    object Terminated : HealthStatus()
    data class PermissionDenied(val cause: Throwable? = null) : HealthStatus()
}

@Singleton
class ProfileHealthChecker @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_BACKOFF_MS = 200L
        private const val MAX_BACKOFF_MS = 1000L
    }

    suspend fun checkHealth(): HealthStatus {
        val currentUser = auth.currentUser ?: return HealthStatus.Missing
        val userId = currentUser.uid
        ArtifactLogger.d(DiagnosticCategory.AUTH, "PROFILE_CHECK_STARTED")

        var tokenRefreshed = false
        var lastPermissionException: FirebaseFirestoreException? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                val calculatedBackoff = INITIAL_BACKOFF_MS * (1 shl (attempt - 2))
                val backoffMs = calculatedBackoff.coerceAtMost(MAX_BACKOFF_MS)
                delay(backoffMs.milliseconds)
            }

            try {
                return fetchHealthStatus(userId)
            } catch (e: TimeoutCancellationException) {
                ArtifactLogger.e(DiagnosticCategory.AUTH, "PROFILE_CHECK_TIMEOUT")
                return HealthStatus.Missing
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    lastPermissionException = e
                    ArtifactLogger.w(
                        DiagnosticCategory.AUTH,
                        "PROFILE_CHECK_PERMISSION_DENIED_RETRYING",
                        mapOf("attempt" to attempt, "maxAttempts" to MAX_ATTEMPTS)
                    )
                    if (!tokenRefreshed) {
                        try {
                            // Note: getIdToken(true) refreshes FirebaseAuth token in memory,
                            // but Firestore SDK syncs its internal credential provider asynchronously.
                            // The bounded retry loop serves as the practical application-level readiness boundary.
                            currentUser.getIdToken(true).await()
                            tokenRefreshed = true
                            ArtifactLogger.i(DiagnosticCategory.AUTH, "PROFILE_CHECK_TOKEN_REFRESH_SUCCESS")
                        } catch (tokenException: Exception) {
                            ArtifactLogger.w(
                                DiagnosticCategory.AUTH,
                                "PROFILE_CHECK_TOKEN_REFRESH_FAILED",
                                throwable = tokenException
                            )
                            if (tokenException is FirebaseAuthInvalidUserException) {
                                return HealthStatus.Unrecoverable
                            }
                        }
                    }
                } else {
                    ArtifactLogger.e(DiagnosticCategory.AUTH, "PROFILE_CHECK_FAILED", throwable = e)
                    return HealthStatus.Missing
                }
            } catch (e: Exception) {
                ArtifactLogger.e(DiagnosticCategory.AUTH, "PROFILE_CHECK_FAILED", throwable = e)
                if (e is FirebaseAuthInvalidUserException) {
                    return HealthStatus.Unrecoverable
                }
                return HealthStatus.Missing
            }
        }

        ArtifactLogger.e(
            DiagnosticCategory.AUTH,
            "PROFILE_CHECK_PERMISSION_DENIED_PERSISTENT",
            throwable = lastPermissionException
        )
        return HealthStatus.PermissionDenied(lastPermissionException)
    }

    private suspend fun fetchHealthStatus(userId: String): HealthStatus {
        val userRef = firestore.collection("users").document(userId)
        val privateRef = userRef.collection("private").document("settings")

        ArtifactLogger.d(DiagnosticCategory.AUTH, "PROFILE_CHECK_FETCH_USER")
        val userSnapshot = withTimeout(10.seconds) {
            userRef.get().await()
        }
        if (!userSnapshot.exists()) {
            ArtifactLogger.w(DiagnosticCategory.AUTH, "PROFILE_CHECK_USER_MISSING")
            return HealthStatus.Missing
        }

        // Verify basic fields
        val user = userSnapshot.toObject(User::class.java)?.copy(id = userSnapshot.id)
        if (user == null || user.anonymousId.isBlank() || user.anonymousName.isBlank()) {
            ArtifactLogger.w(DiagnosticCategory.AUTH, "PROFILE_CHECK_IDENTITY_MISSING")
            return HealthStatus.RepairRequired
        }

        ArtifactLogger.d(DiagnosticCategory.AUTH, "PROFILE_CHECK_FETCH_PRIVATE")
        val privateSnapshot = withTimeout(10.seconds) {
            privateRef.get().await()
        }
        if (!privateSnapshot.exists()) {
            ArtifactLogger.w(DiagnosticCategory.AUTH, "PROFILE_CHECK_PRIVATE_MISSING")
            return HealthStatus.RepairRequired
        }

        val privateSettings = privateSnapshot.toObject(UserPrivateSettings::class.java)
        if (privateSettings?.accountStatus == "TERMINATED") {
            ArtifactLogger.e(DiagnosticCategory.AUTH, "PROFILE_CHECK_TERMINATED")
            return HealthStatus.Terminated
        }

        ArtifactLogger.i(DiagnosticCategory.AUTH, "PROFILE_CHECK_SUCCESS")
        return HealthStatus.Healthy
    }
}
