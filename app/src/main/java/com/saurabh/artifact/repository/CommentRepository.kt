package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.model.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.saurabh.artifact.data.paging.CommentPagingSource
import com.saurabh.artifact.service.ModerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val moderationService: ModerationService,
) {

    /**
     * Uploads a reflection to Firestore.
     * Enforces Intimacy-Centered Reflection System rules.
     */
    suspend fun submitReflection(
        artifactId: String,
        userId: String,
        content: String,
        visibility: VisibilityLayer = VisibilityLayer.BRIDGE,
        authorType: AuthorType = AuthorType.PSEUDONYM,
        revealAt: Timestamp? = null,
        authorName: String = "Quiet Presence",
        authorAvatarSeed: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            val artifactDoc = artifactRef.get().await()
            val ownerId = artifactDoc.getString("userId")

            // 1. Analyze for Auto-Moderation
            val moderationAnalysis = moderationService.analyzeLocal(content)
            
            if (moderationAnalysis.isCritical) {
                return@withContext Result.failure(Exception(moderationAnalysis.message))
            }

            val initialState = when {
                moderationAnalysis.isSensitive -> CommentModerationState.PENDING
                else -> CommentModerationState.APPROVED
            }

            // 2. Create Reflection Document
            val commentId = UUID.randomUUID().toString()
            val isQuiet = authorType == AuthorType.QUIET_PRESENCE
            val reflection = ArtifactComment(
                id = commentId,
                artifactId = artifactId,
                authorId = userId,
                artifactOwnerId = ownerId ?: "", // Cached for security rules
                authorAnonymousName = if (isQuiet) null else authorName,
                authorAvatarSeed = if (isQuiet) "ANONYMOUS_AURA" else authorAvatarSeed,
                content = content,
                visibilityLayer = visibility,
                authorType = authorType,
                createdAt = Timestamp.now(),
                revealAt = revealAt,
                moderationState = initialState
            )

            // 3. Atomically add reflection (Zero-Trust: count updated by backend)
            val commentRef = firestore.collection("comments").document(commentId)
            
            commentRef.set(reflection).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("CommentRepository", "Failed to submit reflection", e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Returns a Pager for comments of a specific artifact.
     */
    fun getCommentsPager(
        artifactId: String,
        currentUserId: String,
        artifactOwnerId: String
    ): Flow<PagingData<ArtifactComment>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false,
                prefetchDistance = 5
            ),
            pagingSourceFactory = {
                CommentPagingSource(firestore, artifactId, currentUserId, artifactOwnerId)
            }
        ).flow
    }

    suspend fun getCommentById(commentId: String): Result<ArtifactComment> = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("comments").document(commentId).get().await()
            if (doc.exists()) {
                val comment = doc.toObject(ArtifactComment::class.java)?.copy(id = doc.id)
                if (comment != null) {
                    Result.success(comment)
                } else {
                    Result.failure(AppError.NotFound("Comment", commentId))
                }
            } else {
                Result.failure(AppError.NotFound("Comment", commentId))
            }
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Updates the creator's reaction to a specific comment.
     * This is a private signal from the artifact owner to the reflection author.
     */
    suspend fun reactToComment(commentId: String, type: ReactionType): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firestore.collection("comments").document(commentId)
                .update("creatorReaction", type.name)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("CommentRepository", "Failed to react to comment", e)
            Result.failure(AppError.from(e))
        }
    }

}
