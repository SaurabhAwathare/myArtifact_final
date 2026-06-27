package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.data.paging.CommentPagingSource
import com.saurabh.artifact.model.*
import com.saurabh.artifact.service.ModerationService
import com.saurabh.artifact.worker.InteractionSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val moderationService: ModerationService,
    private val pendingInteractionDao: PendingInteractionDao,
    private val gson: Gson
) {

    /**
     * Enqueues a reflection to be uploaded to Firestore.
     * Enforces Intimacy-Centered Reflection System rules.
     * OFFLINE-FIRST: Queues interaction locally and triggers worker.
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
            // 1. Analyze for Auto-Moderation (Immediate local feedback)
            val moderationAnalysis = moderationService.analyzeLocal(content)
            
            if (moderationAnalysis.isCritical) {
                return@withContext Result.failure(Exception(moderationAnalysis.message))
            }

            val initialState = when {
                moderationAnalysis.isSensitive -> CommentModerationState.PENDING
                else -> CommentModerationState.APPROVED
            }

            // 2. Fetch ownerId (Optimistic/Cached if possible, but for queue we need it in payload)
            // In a real production app, we might check a local artifact cache first.
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            val artifactDoc = artifactRef.get().await()
            val ownerId = artifactDoc.getString("userId") ?: ""

            // 3. Create Sync Payload
            val commentId = UUID.randomUUID().toString()
            val isQuiet = authorType == AuthorType.QUIET_PRESENCE
            val payload = CommentSyncPayload(
                commentId = commentId,
                artifactId = artifactId,
                content = content,
                visibility = visibility,
                authorType = authorType,
                revealAtMillis = revealAt?.toDate()?.time,
                authorName = if (isQuiet) null else authorName,
                authorAvatarSeed = if (isQuiet) "ANONYMOUS_AURA" else authorAvatarSeed,
                artifactOwnerId = ownerId,
                moderationState = initialState,
                createdAtMillis = System.currentTimeMillis()
            )

            // 4. Enqueue Interaction
            val pending = PendingInteractionEntity(
                userId = userId,
                artifactId = artifactId,
                interactionType = InteractionType.COMMENT,
                action = InteractionAction.ADD,
                metadata = gson.toJson(payload)
            )
            
            pendingInteractionDao.insert(pending)
            InteractionSyncWorker.enqueue(context)

            Result.success(Unit)
        } catch (e: Exception) {
            // Fallback for offline: if fetching artifactDoc fails, we can't reliably get ownerId
            // but for Phase 20A we maintain the current requirement of knowing the owner for security rules.
            Log.e("CommentRepository", "Failed to queue reflection", e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Internal synchronization method for reflections.
     * Performs direct Firestore write.
     */
    internal suspend fun syncCommentToFirestore(userId: String, payload: CommentSyncPayload): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val reflection = ArtifactComment(
                id = payload.commentId,
                artifactId = payload.artifactId,
                authorId = userId,
                artifactOwnerId = payload.artifactOwnerId,
                authorAnonymousName = payload.authorName,
                authorAvatarSeed = payload.authorAvatarSeed,
                content = payload.content,
                visibilityLayer = payload.visibility,
                authorType = payload.authorType,
                createdAt = Timestamp(java.util.Date(payload.createdAtMillis)),
                revealAt = payload.revealAtMillis?.let { Timestamp(java.util.Date(it)) },
                moderationState = payload.moderationState
            )

            val commentRef = firestore.collection("comments").document(payload.commentId)
            commentRef.set(reflection).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
     * OFFLINE-FIRST: Queues interaction locally and triggers worker.
     */
    suspend fun reactToComment(commentId: String, type: ReactionType): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            
            val pending = PendingInteractionEntity(
                userId = userId,
                artifactId = commentId, // Using artifactId field to store commentId
                interactionType = InteractionType.COMMENT_REACTION,
                action = InteractionAction.ADD,
                metadata = type.id
            )
            
            // Clean up existing reactions for this comment in the queue
            pendingInteractionDao.deleteByType(commentId, userId, InteractionType.COMMENT_REACTION)
            pendingInteractionDao.insert(pending)
            InteractionSyncWorker.enqueue(context)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("CommentRepository", "Failed to queue comment reaction", e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Internal synchronization method for comment reactions.
     * Performs direct Firestore update.
     */
    internal suspend fun syncCommentReactionToFirestore(commentId: String, type: ReactionType): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firestore.collection("comments").document(commentId)
                .update("creatorReaction", type.id)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
