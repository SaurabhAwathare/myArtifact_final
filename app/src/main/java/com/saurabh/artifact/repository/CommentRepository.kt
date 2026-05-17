package com.saurabh.artifact.repository

import android.net.Uri
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
    private val storage: FirebaseStorage
) {

    /**
     * Uploads a reflection (comment) to Storage and creates a document in Firestore.
     * Enforces Hidden Comments Mode privacy rules.
     */
    suspend fun submitReflection(
        artifactId: String,
        userId: String,
        content: String,
        audioFilePath: String? = null,
        visibility: CommentVisibilityMode = CommentVisibilityMode.HIDDEN,
        isAnonymous: Boolean = false,
        authorName: String = "Anonymous Soul",
        authorEmoji: String = "✨"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var audioUrl: String? = null
            
            // 1. Upload audio if present
            audioFilePath?.let { path ->
                val fileUri = Uri.fromFile(File(path))
                val storageRef = storage.reference.child("reflections/$artifactId/${UUID.randomUUID()}.m4a")
                storageRef.putFile(fileUri).await()
                audioUrl = storageRef.downloadUrl.await().toString()
            }

            // 2. Create Comment Document
            val commentId = UUID.randomUUID().toString()
            val comment = ArtifactComment(
                id = commentId,
                artifactId = artifactId,
                authorId = userId,
                authorDisplayName = if (isAnonymous) null else authorName,
                authorEmoji = authorEmoji,
                content = content,
                audioUrl = audioUrl,
                visibility = visibility,
                createdAt = Timestamp.now(),
                isAnonymous = isAnonymous,
                moderationState = CommentModerationState.PENDING
            )

            // 3. Atomically add comment and update artifact count
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            val commentRef = firestore.collection("comments").document(commentId)
            
            firestore.runTransaction { transaction ->
                transaction.set(commentRef, comment)
                transaction.update(artifactRef, "commentCount", FieldValue.increment(1))
                
                // Also add to creator's inbox (denormalized for fast access)
                // Note: We'd normally get the artifact owner ID first
            }.await()

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
            val filtered = allComments.filter { comment ->
                comment.authorId == currentUserId || 
                artifactOwnerId == currentUserId || 
                comment.visibility == CommentVisibilityMode.PUBLIC
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
