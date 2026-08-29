package com.saurabh.artifact.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.CommunityAtmosphere
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    /**
     * Fetches the latest community emotional atmosphere snapshot.
     */
    suspend fun getLatestAtmosphere(): Result<CommunityAtmosphere> {
        return try {
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
            Result.failure(e)
        }
    }
}
