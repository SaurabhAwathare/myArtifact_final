package com.saurabh.artifact.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.domain.SafetyPolicy
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Paginates through a user's saved artifacts in Firestore.
 * Ensures O(1) query growth by using cursor-based loading and hydrating documents in chunks.
 */
class SavedArtifactPagingSource(
    private val firestore: FirebaseFirestore,
    private val userId: String,
    private val safetyPolicy: SafetyPolicy,
    private val visibilityFilter: ArtifactVisibilityFilter
) : PagingSource<DocumentSnapshot, Artifact>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, Artifact>): DocumentSnapshot? {
        return null // Always refresh from start
    }

    override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, Artifact> {
        return withContext(Dispatchers.IO) {
            try {
                val currentPageSize = params.loadSize
                
                // 1. Fetch Page of Saved IDs
                var query = firestore.collection("users").document(userId)
                    .collection("savedArtifacts")
                    .orderBy("savedAt", Query.Direction.DESCENDING)
                    .limit(currentPageSize.toLong())

                params.key?.let { cursor ->
                    query = query.startAfter(cursor)
                }

                val idSnapshot = query.get().await()
                if (idSnapshot.isEmpty) {
                    return@withContext LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
                }

                val artifactIds = idSnapshot.documents.map { it.id }
                val lastIdDoc = idSnapshot.documents.lastOrNull()

                // 2. Hydrate Artifact Documents (Chunked to respect whereIn limits)
                val fetchedArtifacts = mutableMapOf<String, Artifact>()
                val chunks = artifactIds.chunked(10)
                
                for (chunk in chunks) {
                    val docs = firestore.collection("artifacts")
                        .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                        .get().await()
                    
                    docs.documents.forEach { doc ->
                        doc.toObject(Artifact::class.java)?.copy(id = doc.id)?.let { artifact ->
                            fetchedArtifacts[doc.id] = artifact
                        }
                    }
                }

                // 3. Apply Safety & Suppression Policy
                val suppressedIds = visibilityFilter.getSuppressedIdsSnapshot(userId)
                
                val finalItems = artifactIds.mapNotNull { id ->
                    val artifact = fetchedArtifacts[id] ?: return@mapNotNull null
                    
                    // Reconstruct transient metadata for policy check
                    // Note: We'd need to fetch actual counts if they aren't in the Artifact doc,
                    // but the base Artifact doc should have them for discovery.
                    val isEligible = safetyPolicy.isEligibleForDiscovery(
                        artifact = artifact,
                        currentUserId = userId,
                        isSuppressedByUser = suppressedIds.contains(id)
                    )

                    if (isEligible) artifact else null
                }

                LoadResult.Page(
                    data = finalItems,
                    prevKey = null,
                    nextKey = if (idSnapshot.documents.size < currentPageSize) null else lastIdDoc
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }
}
