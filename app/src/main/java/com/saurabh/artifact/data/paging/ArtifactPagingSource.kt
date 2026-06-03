package com.saurabh.artifact.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.model.Artifact
import kotlinx.coroutines.tasks.await

class ArtifactPagingSource(
    private val firestore: FirebaseFirestore,
    private val currentUserId: String,
    private val emotion: String? = null
) : PagingSource<DocumentSnapshot, Artifact>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, Artifact>): DocumentSnapshot? {
        return null
    }

    override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, Artifact> {
        return try {
            var query = firestore.collection("artifacts")
                .whereEqualTo("isPublic", true)
                .orderBy("createdAt", Query.Direction.DESCENDING)

            if (!emotion.isNullOrEmpty() && emotion != "All") {
                val relatedEmotions = getRelatedEmotions(emotion)
                query = query.whereIn("emotion", relatedEmotions)
            }

            val key = params.key
            if (key != null) {
                query = query.startAfter(key)
            }

            val snapshot = query.limit(params.loadSize.toLong()).get().await()
            val artifacts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                snapshot.documents.mapNotNull { doc ->
                    val safetyCount = doc.getLong("safetyConcernCount") ?: 0L
                    val reportCount = doc.getLong("reportCount") ?: 0L
                    val reporterIds = doc.get("reporterIds") as? List<*> ?: emptyList<String>()
                    
                    if (safetyCount >= 3 || reportCount >= 3 || reporterIds.contains(currentUserId)) {
                        // Hide content that has been flagged multiple times or by the current user
                        null
                    } else {
                        doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                    }
                }
            }

            LoadResult.Page(
                data = artifacts,
                prevKey = null,
                nextKey = if (artifacts.size < params.loadSize) null else snapshot.documents.lastOrNull()
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private fun getRelatedEmotions(emotion: String): List<String> {
        return when (emotion) {
            "Sad" -> listOf("Sad", "Lonely")
            "Lonely" -> listOf("Lonely", "Sad")
            "Anxious" -> listOf("Anxious", "Angry")
            "Happy" -> listOf("Happy", "Motivated")
            "Motivated" -> listOf("Motivated", "Happy")
            else -> listOf(emotion)
        }
    }
}
