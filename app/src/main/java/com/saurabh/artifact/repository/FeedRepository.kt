package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val artifactRepository: ArtifactRepository
) {

    /**
     * Fetches artifacts from creators the user follows.
     */
    suspend fun getFollowedArtifacts(userId: String, limit: Long = 20): List<Artifact> = withContext(Dispatchers.IO) {
        return@withContext try {
            val followedUserIds = firestore.collection("follows")
                .whereEqualTo("followerId", userId)
                .get()
                .await()
                .documents
                .mapNotNull { it.getString("followingId") }

            if (followedUserIds.isEmpty()) return@withContext emptyList()

            // Firestore 'whereIn' is limited to 10-30 items.
            val chunks = followedUserIds.chunked(10)
            val allArtifacts = mutableListOf<Artifact>()
            for (chunk in chunks) {
                val snapshot = firestore.collection("artifacts")
                    .whereIn("userId", chunk)
                    .whereEqualTo("isPublic", true)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit)
                    .get()
                    .await()
                
                val mappedChunk = withContext(Dispatchers.Default) {
                    snapshot.toObjects(Artifact::class.java).mapIndexed { i, a ->
                        a.copy(id = snapshot.documents[i].id)
                    }
                }
                allArtifacts.addAll(mappedChunk)
            }
            withContext(Dispatchers.Default) {
                allArtifacts.sortedByDescending { it.createdAt }
            }
        } catch (e: Exception) {
            Log.e("FeedRepository", "Error fetching followed artifacts", e)
            emptyList()
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
     * Fetches discovery candidates based on emotional compatibility.
     * For now, it delegates to ArtifactRepository and adds scoring logic.
     */
    suspend fun getDiscoveryCandidates(limit: Long = 20): List<Artifact> {
        return artifactRepository.getCandidateArtifacts(limit)
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
