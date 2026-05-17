package com.saurabh.artifact.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.saurabh.artifact.MainActivity
import com.saurabh.artifact.R

/**
 * Foundational notification infrastructure for an emotionally intelligent app.
 * Focuses on centralized, reusable, and respectful engagement mechanics.
 */
object NotificationHelper {

    const val CHANNEL_ID_INTERACTIONS = "interactions_channel"
    const val CHANNEL_NAME_INTERACTIONS = "Interactions"
    const val CHANNEL_DESC_INTERACTIONS = "Notifications for reactions and replies to your artifacts."

    const val CHANNEL_ID_REMINDERS = "reminders_channel"
    const val CHANNEL_NAME_REMINDERS = "Reminders"
    const val CHANNEL_DESC_REMINDERS = "Gentle nudges to help you stay connected with your feelings."

    const val CHANNEL_ID_UPLOADS = "uploads_channel"
    const val CHANNEL_NAME_UPLOADS = "Upload Status"
    const val CHANNEL_DESC_UPLOADS = "Status updates for your artifact uploads."

    /**
     * Initializes all notification channels for the app.
     * Should be called in the Application class.
     */
    fun initNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Create Interactions Channel (IMPORTANCE_DEFAULT)
            val interactionsChannel = NotificationChannel(
                CHANNEL_ID_INTERACTIONS,
                CHANNEL_NAME_INTERACTIONS,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESC_INTERACTIONS
            }
            
            // Create Reminders Channel (IMPORTANCE_LOW for subtlety)
            val remindersChannel = NotificationChannel(
                CHANNEL_ID_REMINDERS,
                CHANNEL_NAME_REMINDERS,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC_REMINDERS
            }

            // Create Uploads Channel (IMPORTANCE_LOW - non-interruptive)
            val uploadsChannel = NotificationChannel(
                CHANNEL_ID_UPLOADS,
                CHANNEL_NAME_UPLOADS,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC_UPLOADS
            }
            
            notificationManager.createNotificationChannels(listOf(interactionsChannel, remindersChannel, uploadsChannel))
            
            // Note: RecordingService manages its own foreground service channel (recording_channel)
        }
    }

    /**
     * Checks if the app has permission to post notifications.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Shows a standard notification for artifact interactions.
     * 
     * @param context App context
     * @param title Notification title
     * @param message Notification body
     * @param artifactId Optional ID to navigate to a specific artifact on click
     */
    fun showInteractionNotification(
        context: Context,
        title: String,
        message: String,
        artifactId: String? = null
    ) {
        if (!hasNotificationPermission(context)) {
            Log.w("NotificationHelper", "Skipping interaction notification: Permission not granted.")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("artifactId", artifactId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_INTERACTIONS)
            .setSmallIcon(R.mipmap.ic_launcher) // Standardize to mipmap for compatibility
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        with(NotificationManagerCompat.from(context)) {
            val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            try {
                notify(notificationId, builder.build())
            } catch (e: SecurityException) {
                Log.e("NotificationHelper", "SecurityException while showing interaction notification", e)
            }
        }
    }

    /**
     * Shows a gentle reminder for daily reflection.
     */
    fun showReminderNotification(context: Context, title: String, message: String) {
        if (!hasNotificationPermission(context)) {
            Log.w("NotificationHelper", "Skipping reminder notification: Permission not granted.")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_REMINDERS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(context).notify(2001, builder.build())
        } catch (e: SecurityException) {
            Log.e("NotificationHelper", "SecurityException while showing reminder notification", e)
        }
    }

    /**
     * Shows a subtle confirmation of a successful upload.
     */
    fun showUploadSuccessNotification(context: Context, title: String) {
        if (!hasNotificationPermission(context)) {
            Log.w("NotificationHelper", "Skipping upload success notification: Permission not granted.")
            return
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_UPLOADS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("myArtifact Shared")
            .setContentText("\"$title\" is now live.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(3001, builder.build())
        } catch (e: SecurityException) {
            Log.e("NotificationHelper", "SecurityException while showing upload success notification", e)
        }
    }
}
