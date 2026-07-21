package com.saurabh.artifact.repository

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.model.NotificationItem
import com.saurabh.artifact.model.NotificationType
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

    // Optimization: Pre-compute valid types for fast lookup
    private val validNotificationTypes = NotificationType.entries.map { it.name }.toSet()

    fun listenNotifications(userId: String): Flow<List<NotificationItem>> = callbackFlow {
        if (userId.isEmpty()) {
            ArtifactLogger.w(DiagnosticCategory.APP, "NOTIF_LISTEN_EMPTY_USER")
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
                        ArtifactLogger.e(DiagnosticCategory.APP, "NOTIF_LISTENER_ERROR", mapOf("code" to error.code.name), error)
                        lastErrorTime = now
                    }
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                
                launch(Dispatchers.Default) {
                    val notifications = snapshot?.documents?.mapNotNull { doc ->
                        val typeStr = doc.getString("type")

                        if (typeStr == null) {
                            ArtifactLogger.w(DiagnosticCategory.APP, "NOTIF_SKIP_MISSING_TYPE", mapOf("notifId" to doc.id))
                            return@mapNotNull null
                        }

                        if (typeStr !in validNotificationTypes) {
                            ArtifactLogger.w(DiagnosticCategory.APP, "NOTIF_SKIP_UNKNOWN_TYPE", mapOf("notifId" to doc.id, "type" to typeStr))
                            return@mapNotNull null
                        }

                        try {
                            doc.toObject(NotificationItem::class.java)?.copy(id = doc.id)
                        } catch (e: RuntimeException) {
                            ArtifactLogger.e(DiagnosticCategory.APP, "NOTIF_DESERIALIZATION_FAILED", mapOf("notifId" to doc.id), e)
                            null
                        }
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

    /**
     * Efficiently marks all unread notifications for a user as read.
     * Used to clear the awareness state when the user enters the notification center.
     */
    suspend fun markAllAsRead(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val unread = notificationsCollection
                .whereEqualTo("userId", userId)
                .whereEqualTo("isRead", false)
                .get()
                .await()
            
            if (unread.isEmpty) return@withContext Result.success(Unit)
            
            firestore.runBatch { batch ->
                unread.documents.forEach { doc ->
                    batch.update(doc.reference, "isRead", true)
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.APP, "NOTIF_MARK_ALL_READ_FAILED", throwable = e)
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
            ArtifactLogger.e(DiagnosticCategory.APP, "NOTIF_CREATE_FAILED", throwable = e)
            Result.failure(e)
        }
    }
}
