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
        
        // 1. Handle Notification Payload (Automatic background handling)
        remoteMessage.notification?.let { notification ->
            val title = notification.title ?: "myArtifact"
            val body = notification.body ?: "Someone engaged with your artifact 💬"
            NotificationHelper.showInteractionNotification(this, title, body, remoteMessage.data["artifactId"])
        }

        // 2. Handle Data Payload (For custom handling or when app is in foreground)
        if (remoteMessage.data.isNotEmpty()) {
            val title = remoteMessage.data["title"] ?: "New Interaction"
            val message = remoteMessage.data["message"] ?: "Someone sent you a reaction!"
            val artifactId = remoteMessage.data["artifactId"]
            NotificationHelper.showInteractionNotification(this, title, message, artifactId)
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
