package com.saurabh.artifact.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.CommunityAtmosphere
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val firebaseAuth: FirebaseAuth? = null,
    private val authRepository: AuthRepository? = null
) {
    /**
     * Fetches the latest community emotional atmosphere snapshot.
     */
    suspend fun getLatestAtmosphere(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result<CommunityAtmosphere> {
        return try {
            if (authRepository != null) {
                val isRestored = authRepository.awaitAuthRestoration(timeoutMs)
                if (!isRestored) {
                    return Result.failure(TimeoutException("Authentication restoration timed out"))
                }
            }
            val currentUser = authRepository?.currentUser?.value ?: firebaseAuth?.currentUser
            if (currentUser == null && (authRepository != null || firebaseAuth != null)) {
                return Result.success(CommunityAtmosphere(status = "UNAUTHENTICATED"))
            }

            val doc = firestore.collection("community").document("atmosphere")
                .get()
                .await()
            
            val atmosphere = doc.toObject(CommunityAtmosphere::class.java)
            if (atmosphere != null) {
                Result.success(atmosphere)
            } else {
                Result.success(CommunityAtmosphere(status = "INSUFFICIENT_DATA"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
