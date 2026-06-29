package com.saurabh.artifact.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Filter
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.CommentModerationState
import com.saurabh.artifact.model.VisibilityLayer
import com.saurabh.artifact.util.ArtifactLogger
import kotlinx.coroutines.tasks.await

class CommentPagingSource(
    private val firestore: FirebaseFirestore,
    private val artifactId: String,
    private val currentUserId: String,
    private val artifactOwnerId: String
) : PagingSource<DocumentSnapshot, ArtifactComment>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, ArtifactComment>): DocumentSnapshot? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey
                ?: state.closestPageToPosition(anchorPosition)?.nextKey
        }
    }

    override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, ArtifactComment> {
        return try {
            val isOwner = currentUserId == artifactOwnerId
            val now = Timestamp.now()

            ArtifactLogger.d("CommentPagingSource", "Loading comments: artifact=$artifactId, user=$currentUserId, isOwner=$isOwner")

            val baseQuery = firestore.collection("comments")
                .whereEqualTo("artifactId", artifactId)
                .whereEqualTo("artifactOwnerId", artifactOwnerId)

            // Phase 1 Fix: targeted queries to match security rules
            val secureFilter = if (isOwner) {
                // Owner: authorId == currentUserId OR visibilityLayer != SANCTUARY
                Filter.or(
                    Filter.equalTo("authorId", currentUserId),
                    Filter.inArray("visibilityLayer", listOf(VisibilityLayer.BRIDGE.name, VisibilityLayer.RESONANCE.name))
                )
            } else {
                // Listener: authorId == currentUserId OR (RESONANCE + APPROVED + revealed)
                Filter.or(
                    Filter.equalTo("authorId", currentUserId),
                    Filter.and(
                        Filter.equalTo("visibilityLayer", VisibilityLayer.RESONANCE.name),
                        Filter.equalTo("moderationState", CommentModerationState.APPROVED.name),
                        Filter.or(
                            Filter.equalTo("revealAt", null),
                            Filter.lessThanOrEqualTo("revealAt", now)
                        )
                    )
                )
            }

            var query = baseQuery.where(secureFilter)
                .orderBy("createdAt", Query.Direction.DESCENDING)

            val key = params.key
            if (key != null) {
                query = query.startAfter(key)
            }

            val snapshot = query.limit(params.loadSize.toLong()).get().await()
            
            val comments = snapshot.documents.mapNotNull { doc ->
                val comment = doc.toObject(ArtifactComment::class.java)?.copy(id = doc.id) ?: return@mapNotNull null
                
                // Existing filtering logic remains as a secondary safety layer
                val isAuthor = comment.authorId == currentUserId
                
                val isModerated = comment.reportCount >= 3 || 
                                 comment.reporterIds.contains(currentUserId) ||
                                 comment.moderationState == CommentModerationState.BLOCKED ||
                                 comment.moderationState == CommentModerationState.HIDDEN

                val visible = when (comment.visibilityLayer) {
                    VisibilityLayer.SANCTUARY -> isAuthor
                    VisibilityLayer.BRIDGE -> isAuthor || isOwner
                    VisibilityLayer.RESONANCE -> {
                        val isRevealed = comment.revealAt == null || comment.revealAt <= now
                        isAuthor || isOwner || isRevealed
                    }
                } && !isModerated
                
                if (visible) comment else null
            }

            ArtifactLogger.d("CommentPagingSource", "Successfully loaded ${comments.size} comments")

            LoadResult.Page(
                data = comments,
                prevKey = null,
                nextKey = if (snapshot.size() < params.loadSize) null else snapshot.documents.lastOrNull()
            )
        } catch (e: Exception) {
            val errorCode = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"
            ArtifactLogger.e("CommentPagingSource", "Error loading comments (Code: $errorCode) for artifact=$artifactId, user=$currentUserId. Message: ${e.message}", e)
            LoadResult.Error(e)
        }
    }
}
