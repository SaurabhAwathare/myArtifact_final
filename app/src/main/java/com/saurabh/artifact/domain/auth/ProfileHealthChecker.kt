package com.saurabh.artifact.domain.auth

import android.util.Log
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
    object RepairRequired : HealthStatus()
    object Missing : HealthStatus()
}

@Singleton
class ProfileHealthChecker @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    suspend fun checkHealth(): HealthStatus {
        val currentUser = auth.currentUser ?: return HealthStatus.Missing
        val userId = currentUser.uid

        return try {
            val userRef = firestore.collection("users").document(userId)
            val privateRef = userRef.collection("private").document("settings")

            Log.d("APP_FLOW", "PROFILE_CHECK_FETCH_USER")
            val userSnapshot = withTimeout(10.seconds) {
                userRef.get().await()
            }
            if (!userSnapshot.exists()) {
                Log.w("ProfileHealth", "User document missing for $userId")
                return HealthStatus.Missing
            }

            // Verify basic fields
            val user = userSnapshot.toObject(User::class.java)
            if (user == null || user.anonymousId.isBlank() || user.anonymousName.isBlank()) {
                Log.w("ProfileHealth", "User document malformed for $userId")
                return HealthStatus.RepairRequired
            }

            Log.d("APP_FLOW", "PROFILE_CHECK_FETCH_PRIVATE")
            val privateSnapshot = withTimeout(10.seconds) {
                privateRef.get().await()
            }
            if (!privateSnapshot.exists()) {
                Log.w("ProfileHealth", "Private settings missing for $userId")
                return HealthStatus.RepairRequired
            }

            Log.d("APP_FLOW", "PROFILE_CHECK_SUCCESS")
            HealthStatus.Healthy
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("APP_FLOW", "PROFILE_CHECK_TIMEOUT", e)
            HealthStatus.Missing
        } catch (e: Exception) {
            Log.e("APP_FLOW", "PROFILE_CHECK_FAILED", e)
            HealthStatus.Missing // Treat as missing to trigger recovery
        }
    }
}
