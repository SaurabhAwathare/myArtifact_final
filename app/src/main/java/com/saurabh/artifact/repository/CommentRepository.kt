package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.saurabh.artifact.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val notificationRepository: NotificationRepository
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

            // 2. Create Reflection Document
            val commentId = UUID.randomUUID().toString()
            val reflection = ArtifactComment(
                id = commentId,
                artifactId = artifactId,
                authorId = userId,
                artifactOwnerId = ownerId ?: "", // Cached for security rules
                authorAnonymousName = if (authorType == AuthorType.QUIET_PRESENCE) null else authorName,
                authorAvatarSeed = authorAvatarSeed,
                content = content,
                visibilityLayer = visibility,
                authorType = authorType,
                createdAt = Timestamp.now(),
                revealAt = revealAt,
                moderationState = CommentModerationState.PENDING
            )

            // 3. Atomically add reflection and update artifact count
            val commentRef = firestore.collection("comments").document(commentId)
            
            firestore.runTransaction { transaction ->
                transaction.set(commentRef, reflection)
                transaction.update(artifactRef, "commentCount", FieldValue.increment(1))
            }.await()

            // Notify owner if it's not their own artifact
            if (ownerId != null && ownerId != userId) {
                notificationRepository.createNotification(
                    userId = ownerId,
                    message = notificationRepository.getReflectionMessage(artifactDoc.getString("title")),
                    artifactId = artifactId,
                    type = NotificationType.REFLECTION
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("CommentRepository", "Failed to submit reflection", e)
            Result.failure(e)
        }
    }

    /**
     * Listens to comments for a specific artifact.
     * Logic is filtered here, but MUST be backed by Firestore Security Rules.
     */
    fun getComments(artifactId: String, currentUserId: String, artifactOwnerId: String): Flow<List<ArtifactComment>> = callbackFlow {
        val query = firestore.collection("comments")
            .whereEqualTo("artifactId", artifactId)
            .orderBy("createdAt", Query.Direction.DESCENDING)

        val subscription = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(emptyList())
                return@addSnapshotListener
            }

            val allComments = snapshot?.documents?.mapNotNull { doc ->
                doc.toObject(ArtifactComment::class.java)?.copy(id = doc.id)
            } ?: emptyList()

            // Client-side filtering as a secondary safety layer
            val now = Timestamp.now()
            val filtered = allComments.filter { comment ->
                val isAuthor = comment.authorId == currentUserId
                val isOwner = artifactOwnerId == currentUserId
                
                when (comment.visibilityLayer) {
                    VisibilityLayer.SANCTUARY -> isAuthor
                    VisibilityLayer.BRIDGE -> isAuthor || isOwner
                    VisibilityLayer.RESONANCE -> {
                        // Resonances are visible to all if revealed, otherwise only author/owner
                        val isRevealed = comment.revealAt == null || comment.revealAt <= now
                        isAuthor || isOwner || isRevealed
                    }
                }
            }
            
            trySend(filtered)
        }
        awaitClose { subscription.remove() }
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
            Result.failure(e)
        }
    }

    /**
     * Fetches emotional insights aggregated for the creator.
     */
    suspend fun getEmotionalSummary(artifactId: String): EmotionalResponseSummary? = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("artifacts").document(artifactId)
                .collection("insights").document("summary")
                .get().await()
            doc.toObject(EmotionalResponseSummary::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
