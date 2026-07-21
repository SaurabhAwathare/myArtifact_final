package com.saurabh.artifact.domain.auth

import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.User
import com.saurabh.artifact.model.UserPrivateSettings
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

sealed class HealthStatus {
    object Healthy : HealthStatus()
    data class Corrupted(val reasons: List<String>) : HealthStatus()
    object RepairRequired : HealthStatus()
    object Unrecoverable : HealthStatus()
    object Missing : HealthStatus()
}

@Singleton
class ProfileHealthChecker @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val profileRepairService: ProfileRepairService
) {
    suspend fun checkHealth(): HealthStatus {
        val currentUser = auth.currentUser ?: return HealthStatus.Missing
        val userId = currentUser.uid

        return try {
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

            // Verify basic fields using the repair service's validation logic
            val (user, needsRepair) = profileRepairService.loadAndRepair(userSnapshot)
            if (needsRepair) {
                ArtifactLogger.w(DiagnosticCategory.AUTH, "PROFILE_CHECK_REPAIR_REQUIRED")
                return HealthStatus.RepairRequired
            }

            if (user.anonymousId.isBlank() || user.anonymousName.isBlank()) {
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

            ArtifactLogger.i(DiagnosticCategory.AUTH, "PROFILE_CHECK_SUCCESS")
            HealthStatus.Healthy
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ArtifactLogger.e(DiagnosticCategory.AUTH, "PROFILE_CHECK_TIMEOUT")
            HealthStatus.Missing
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.AUTH, "PROFILE_CHECK_FAILED", throwable = e)
            HealthStatus.Missing // Treat as missing to trigger recovery
        }
    }
}
