package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.model.NotificationItem
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
        artifactId: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val notificationRef = notificationsCollection.document()
            val notification = NotificationItem(
                id = notificationRef.id,
                userId = userId,
                message = message,
                artifactId = artifactId,
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

    fun getEmpatheticMessage(type: ReactionType): String {
        return when (type) {
            ReactionType.I_HEAR_YOU -> "Someone is listening to your heart 👂"
            ReactionType.RELATABLE -> "Someone found your words relatable 🐚"
            ReactionType.SENDING_STRENGTH -> "Someone sent you strength 💫"
            ReactionType.STAY_STRONG -> "Someone wants you to stay strong 🕯️"
            ReactionType.HOLDING_SPACE -> "Someone is holding space for you 🕯️"
            ReactionType.THANK_YOU -> "Someone is grateful you shared your voice 🙏"
            ReactionType.FELT_DEEPLY -> "Someone felt your words deeply 🌊"
            ReactionType.RESPECTFUL_DISAGREEMENT -> "Someone respectfully sees things differently 🧘"
        }
    }
}
