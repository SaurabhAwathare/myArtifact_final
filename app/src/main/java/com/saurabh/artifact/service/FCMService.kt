package com.saurabh.artifact.service

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.saurabh.artifact.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service for receiving and handling Firebase Cloud Messaging events.
 * Responsible for token management and manual notification display for interactions.
 */
class FCMService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // REDACTED: New token received
        updateTokenInFirestore(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val artifactId = data["artifactId"]
        val userId = data["userId"]
        val recipientId = data["recipientId"]
        val notificationType = data["notificationType"] ?: data["type"]
        val channelId = when {
            data["type"] == "CRITICAL_MODERATION_ALERT" || data["notificationType"] == "CRITICAL_MODERATION_ALERT" -> {
                NotificationHelper.CHANNEL_ID_CRITICAL_ALERTS
            }
            !data["channelId"].isNullOrBlank() -> {
                data["channelId"]!!
            }
            !remoteMessage.notification?.channelId.isNullOrBlank() -> {
                remoteMessage.notification!!.channelId!!
            }
            else -> {
                NotificationHelper.CHANNEL_ID_INTERACTIONS
            }
        }

        // 1. Handle Notification Payload (Foreground or explicit notification block)
        remoteMessage.notification?.let { notification ->
            val title = notification.title ?: "Artifact"
            val body = notification.body ?: "Someone engaged with your artifact 💬"
            
            serviceScope.launch {
                NotificationHelper.showInteractionNotification(
                    context = this@FCMService,
                    title = title,
                    message = body,
                    artifactId = artifactId,
                    userId = userId,
                    recipientId = recipientId,
                    notificationType = notificationType,
                    channelId = channelId,
                )
            }
            return // Prevent duplicate notification from data payload if both exist
        }

        // 2. Handle Data Payload (Fallback for data-only messages)
        if (data.isNotEmpty()) {
            val title = data["title"] ?: if (data["type"] == "CRITICAL_MODERATION_ALERT") "Critical Safety Alert" else "New Interaction"
            val message = data["message"] ?: data["body"] ?: "Someone sent you a reaction!"
            
            serviceScope.launch {
                NotificationHelper.showInteractionNotification(
                    context = this@FCMService,
                    title = title,
                    message = message,
                    artifactId = artifactId,
                    userId = userId,
                    recipientId = recipientId,
                    notificationType = notificationType,
                    channelId = channelId,
                )
            }
        }
    }

    /**
     * Persists the FCM token to the user's private settings in Firestore.
     * This allows the backend to target this specific device for notifications.
     */
    private fun updateTokenInFirestore(token: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val privateSettingsRef = FirebaseFirestore.getInstance()
            .collection("users").document(userId)
            .collection("private").document("settings")
        
        privateSettingsRef.set(mapOf("fcmToken" to token), SetOptions.merge())
            .addOnFailureListener { e -> Log.e("FCM", "Failed to update token", e) }
    }
}
