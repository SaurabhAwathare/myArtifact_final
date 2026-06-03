package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PaginatedArtifacts(
    val artifacts: List<Artifact>,
    val lastVisible: DocumentSnapshot?
)

@Singleton
class FeedRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    /**
     * Fetches artifacts from presences that the user resonates with, supporting pagination.
     */
    suspend fun getResonatingArtifacts(
        userId: String, 
        limit: Int = 20,
        lastVisible: DocumentSnapshot? = null
    ): PaginatedArtifacts = withContext(Dispatchers.IO) {
        return@withContext try {
            val resonatedUserIds = firestore.collection("users")
                .document(userId)
                .collection("resonance_out")
                .get()
                .await()
                .documents
                .map { it.id }

            if (resonatedUserIds.isEmpty()) return@withContext PaginatedArtifacts(emptyList(), null)

            val chunks = resonatedUserIds.chunked(10)
            val allArtifacts = mutableListOf<Artifact>()
            var lastDocInBatch: DocumentSnapshot? = null

            for (chunk in chunks) {
                var query = firestore.collection("artifacts")
                    .whereIn("userId", chunk)
                    .whereEqualTo("isPublic", true)
                    .whereEqualTo("status", ArtifactStatus.ACTIVE.name)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit.toLong())
                
                if (lastVisible != null) {
                    query = query.startAfter(lastVisible)
                }

                val snapshot = query.get().await()
                
                val mappedChunk = snapshot.documents.mapNotNull { doc ->
                    val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                    if (artifact == null || artifact.audioUrl.isEmpty()) return@mapNotNull null
                    
                    val reportCount = doc.getLong("reportCount") ?: 0L
                    val reporterIds = doc.get("reporterIds") as? List<*> ?: emptyList<String>()
                    
                    if (reportCount >= 3 || reporterIds.contains(userId)) {
                        null
                    } else {
                        artifact
                    }
                }
                allArtifacts.addAll(mappedChunk)
                
                if (snapshot.documents.isNotEmpty()) {
                    val currentLast = snapshot.documents.last()
                    if (lastDocInBatch == null || (currentLast.getTimestamp("createdAt") ?: Timestamp.now()) < (lastDocInBatch.getTimestamp("createdAt") ?: Timestamp.now())) {
                        lastDocInBatch = currentLast
                    }
                }
            }
            
            val sorted = allArtifacts.sortedByDescending { it.createdAt }.take(limit)
            PaginatedArtifacts(sorted, lastDocInBatch)
        } catch (e: Exception) {
            Log.e("FeedRepository", "Error fetching followed artifacts", e)
            PaginatedArtifacts(emptyList(), null)
        }
    }

    /**
     * Fetches unfinished listening sessions for a user.
     */
    suspend fun getUnfinishedSessions(userId: String): List<ListeningSession> = withContext(Dispatchers.IO) {
        return@withContext try {
            firestore.collection("listening_sessions")
                .whereEqualTo("userId", userId)
                .whereEqualTo("isCompleted", false)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .limit(5)
                .get()
                .await()
                .toObjects(ListeningSession::class.java)
        } catch (e: Exception) {
            Log.e("FeedRepository", "Error fetching unfinished sessions", e)
            emptyList()
        }
    }

    /**
     * Updates or creates a listening session.
     */
    suspend fun updateListeningSession(session: ListeningSession) = withContext(Dispatchers.IO) {
        try {
            val sessionId = "${session.userId}_${session.artifactId}"
            firestore.collection("listening_sessions")
                .document(sessionId)
                .set(session.copy(updatedAt = Timestamp.now()))
                .await()
        } catch (e: Exception) {
            Log.e("FeedRepository", "Error updating listening session", e)
        }
    }

    /**
     * Fetches discovery candidates based on emotional compatibility with pagination.
     */
    suspend fun getDiscoveryCandidates(
        userId: String? = null,
        limit: Int = 20,
        lastVisible: DocumentSnapshot? = null
    ): PaginatedArtifacts = withContext(Dispatchers.IO) {
        return@withContext try {
            var query = firestore.collection("artifacts")
                .whereEqualTo("isPublic", true)
                .whereEqualTo("status", ArtifactStatus.ACTIVE.name)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())

            if (lastVisible != null) {
                query = query.startAfter(lastVisible)
            }

            val snapshot = query.get().await()
            val artifacts = snapshot.documents.mapNotNull { doc ->
                val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                if (artifact == null || artifact.audioUrl.isEmpty()) return@mapNotNull null

                val reportCount = doc.getLong("reportCount") ?: 0L
                val reporterIds = doc.get("reporterIds") as? List<*> ?: emptyList<String>()

                if (reportCount >= 3 || (userId != null && reporterIds.contains(userId))) {
                    null
                } else {
                    artifact.slimForFeed()
                }
            }
            
            PaginatedArtifacts(artifacts, snapshot.documents.lastOrNull())
        } catch (e: Exception) {
            Log.e("FeedRepository", "Error fetching discovery candidates", e)
            PaginatedArtifacts(emptyList(), null)
        }
    }

    /**
     * Fetches the user's emotional profile for ranking.
     */
    suspend fun getEmotionalProfile(userId: String): EmotionalCompatibilityProfile? = withContext(Dispatchers.IO) {
        return@withContext try {
            firestore.collection("recommendation_profiles")
                .document(userId)
                .get()
                .await()
                .toObject(EmotionalCompatibilityProfile::class.java)
        } catch (e: Exception) {
            Log.e("FeedRepository", "Error fetching emotional profile", e)
            null
        }
    }
}
