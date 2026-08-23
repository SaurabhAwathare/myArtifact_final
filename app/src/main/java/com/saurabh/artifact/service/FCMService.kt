package com.saurabh.artifact.service

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.saurabh.artifact.util.NotificationHelper

/**
 * Service for receiving and handling Firebase Cloud Messaging events.
 * Responsible for token management and manual notification display for interactions.
 */
class FCMService : FirebaseMessagingService() {

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
        val notificationType = data["notificationType"]
        val channelId = data["channelId"] ?: NotificationHelper.CHANNEL_ID_INTERACTIONS

        // 1. Handle Notification Payload (Foreground or explicit notification block)
        remoteMessage.notification?.let { notification ->
            val title = notification.title ?: "Artifact"
            val body = notification.body ?: "Someone engaged with your artifact 💬"
            
            NotificationHelper.showInteractionNotification(
                context = this,
                title = title,
                message = body,
                artifactId = artifactId,
                userId = userId,
                notificationType = notificationType,
                channelId = channelId,
            )
            return // Prevent duplicate notification from data payload if both exist
        }

        // 2. Handle Data Payload (Fallback for data-only messages)
        if (data.isNotEmpty()) {
            val title = data["title"] ?: "New Interaction"
            val message = data["message"] ?: "Someone sent you a reaction!"
            
            NotificationHelper.showInteractionNotification(
                context = this,
                title = title,
                message = message,
                artifactId = artifactId,
                userId = userId,
                notificationType = notificationType,
                channelId = channelId,
            )
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
            .addOnSuccessListener { Log.d("FCM", "Token updated for user: $userId") }
            .addOnFailureListener { e -> Log.e("FCM", "Failed to update token", e) }
    }
}
