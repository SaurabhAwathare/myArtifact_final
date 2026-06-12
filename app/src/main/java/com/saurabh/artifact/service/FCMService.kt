package com.saurabh.artifact.service

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.saurabh.artifact.util.NotificationHelper

class FCMService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New token received: $token")
        updateTokenInFirestore(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val artifactId = data["artifactId"]
        val channelId = data["channelId"] ?: NotificationHelper.CHANNEL_ID_INTERACTIONS

        // 1. Handle Notification Payload
        // If a notification block is present, it will be handled by the system in the background.
        // In the foreground, we handle it manually here.
        remoteMessage.notification?.let { notification ->
            val title = notification.title ?: "myArtifact"
            val body = notification.body ?: "Someone engaged with your artifact 💬"
            
            NotificationHelper.showInteractionNotification(
                context = this,
                title = title,
                message = body,
                artifactId = artifactId,
                channelId = channelId
            )
            return // Early return to prevent duplicate notification from data payload
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
                channelId = channelId
            )
        }
    }

    private fun updateTokenInFirestore(token: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val userRef = FirebaseFirestore.getInstance().collection("users").document(userId)
        
        userRef.set(mapOf("fcmToken" to token), SetOptions.merge())
            .addOnSuccessListener { Log.d("FCM", "Token updated for user: $userId") }
            .addOnFailureListener { e -> Log.e("FCM", "Failed to update token", e) }
    }
}
