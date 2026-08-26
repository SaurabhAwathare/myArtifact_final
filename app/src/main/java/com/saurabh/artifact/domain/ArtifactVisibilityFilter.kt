package com.saurabh.artifact.domain

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.data.local.ReportedArtifactDao
import com.saurabh.artifact.data.local.ReportedArtifactEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A centralized policy engine for determining artifact visibility.
 * This service unifies local suppression rules (like reporting) across all discovery surfaces.
 * Now acts as the bridge for cross-device report synchronization.
 */
@Singleton
class ArtifactVisibilityFilter @Inject constructor(
    private val reportedArtifactDao: ReportedArtifactDao,
    private val firestore: FirebaseFirestore
) {
    /**
     * Fetches a one-shot snapshot of suppressed artifact IDs from Room.
     */
    suspend fun getSuppressedIdsSnapshot(userId: String): Set<String> {
        return reportedArtifactDao.getReportedArtifactIds(userId).toSet()
    }

    /**
     * Provides a reactive stream of suppressed artifact IDs from Room.
     */
    fun observeSuppressedIds(userId: String): Flow<Set<String>> {
        return reportedArtifactDao.observeReportedArtifactIds(userId).map { it.toSet() }
    }

    /**
     * Synchronizes private report markers from Firestore to the local Room database.
     * This provides cross-device persistence for user-specific reports.
     */
    fun syncReportsFromRemote(userId: String, scope: CoroutineScope): Flow<Unit> = callbackFlow {
        if (userId.isEmpty()) {
            trySend(Unit)
            close()
            return@callbackFlow
        }

        // Path: users/{uid}/private/reports/artifacts/{artifactId}
        val collectionRef = firestore.collection("users").document(userId)
            .collection("private").document("reports")
            .collection("artifacts")
        
        val registration = collectionRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                // If it's a permission error or cancelled, we stop syncing gracefully
                trySend(Unit) 
                close(error)
                return@addSnapshotListener
            }
            
            if (snapshot != null) {
                scope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val artifactId = change.document.id
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                reportedArtifactDao.insert(ReportedArtifactEntity(userId, artifactId))
                            }
                            DocumentChange.Type.REMOVED -> {
                                reportedArtifactDao.delete(userId, artifactId)
                            }
                        }
                    }
                    trySend(Unit)
                }
            }
        }
        awaitClose { registration.remove() }
    }
}
