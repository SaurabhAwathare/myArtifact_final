package com.saurabh.artifact.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.model.ArtifactComment
import com.saurabh.artifact.model.CommentModerationState
import com.saurabh.artifact.model.VisibilityLayer
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
            
            var query = firestore.collection("comments")
                .whereEqualTo("artifactId", artifactId)
                .orderBy("createdAt", Query.Direction.DESCENDING)

            val key = params.key
            if (key != null) {
                query = query.startAfter(key)
            }

            val snapshot = query.limit(params.loadSize.toLong()).get().await()
            val now = Timestamp.now()
            
            val comments = snapshot.documents.mapNotNull { doc ->
                val comment = doc.toObject(ArtifactComment::class.java)?.copy(id = doc.id)
                if (comment == null) return@mapNotNull null
                
                // Existing filtering logic from CommentRepository
                val isAuthor = comment.authorId == currentUserId
                
                // Moderation: Auto-hide if reported too many times or blocked
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

            LoadResult.Page(
                data = comments,
                prevKey = null,
                nextKey = if (snapshot.size() < params.loadSize) null else snapshot.documents.lastOrNull()
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
