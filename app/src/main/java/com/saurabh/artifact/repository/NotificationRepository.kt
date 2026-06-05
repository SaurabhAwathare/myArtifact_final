package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.model.NotificationType
import com.saurabh.artifact.model.ReactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val notificationsCollection = firestore.collection("notifications")

    fun listenNotifications(userId: String): Flow<List<NotificationItem>> = callbackFlow {
        if (userId.isEmpty()) {
            Log.w("NotificationRepository", "listenNotifications called with empty userId")
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        var lastErrorTime = 0L
        val subscription = notificationsCollection
            .whereEqualTo("userId", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastErrorTime > 5000) { // 5s throttle for errors
                        Log.e("NotificationRepository", "Notification listener error: ${error.code}", error)
                        lastErrorTime = now
                    }
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                
                launch(Dispatchers.Default) {
                    val notifications = snapshot?.documents?.mapNotNull { 
                        it.toObject(NotificationItem::class.java)?.copy(id = it.id)
                    } ?: emptyList()
                    trySend(notifications)
                }
            }
        awaitClose { subscription.remove() }
    }

    suspend fun markNotificationAsRead(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            notificationsCollection.document(notificationId)
                .update("isRead", true)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createNotification(
        userId: String,
        message: String,
        artifactId: String = "",
        type: NotificationType = NotificationType.RESONANCE
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val notificationRef = notificationsCollection.document()
            val notification = NotificationItem(
                id = notificationRef.id,
                userId = userId,
                message = message,
                artifactId = artifactId,
                type = type,
                createdAt = Timestamp.now(),
                isRead = false
            )
            notificationRef.set(notification).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("NotificationRepository", "Failed to create in-app notification", e)
            Result.failure(e)
        }
    }

    /**
     * Returns a poetic, atmospheric message based on the resonance type.
     * Part of the "Calm Anonymous Resonance Architecture" to reduce social anxiety.
     * 
     * NOTE: This is deprecated for direct UI use. Use UI-layer mappers instead.
     */
    fun getAtmosphericMessage(type: ReactionType): String {
        return "${type.atmosphericLabel} ${type.emoji}"
    }

    /**
     * NOTE: This is deprecated for direct UI use. Use string resources instead.
     */
    fun getReflectionMessage(artifactTitle: String? = null): String {
        return if (artifactTitle != null) {
            "A quiet reflection has arrived in your hearth for \"$artifactTitle\" 🕯️"
        } else {
            "Someone has shared a quiet reflection in your hearth 🕯️"
        }
    }
}
