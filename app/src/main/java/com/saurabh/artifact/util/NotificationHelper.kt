package com.saurabh.artifact.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.saurabh.artifact.R

/**
 * Foundational notification infrastructure for an emotionally intelligent app.
 * Focuses on centralized, reusable, and respectful engagement mechanics.
 */
object NotificationHelper {

    const val CHANNEL_ID_INTERACTIONS = "interactions_channel"
    const val CHANNEL_NAME_INTERACTIONS = "Resonances"
    const val CHANNEL_DESC_INTERACTIONS = "Quiet notifications for reflections and reactions to your shared artifacts."

    const val CHANNEL_ID_REMINDERS = "reminders_channel"
    const val CHANNEL_NAME_REMINDERS = "Reminders"
    const val CHANNEL_DESC_REMINDERS = "Gentle nudges to help you stay connected with your feelings."

    const val CHANNEL_ID_UPLOADS = "uploads_channel"
    const val CHANNEL_NAME_UPLOADS = "Upload Status"
    const val CHANNEL_DESC_UPLOADS = "Status updates for your artifact uploads."

    const val CHANNEL_ID_PLAYBACK = "playback_channel"
    const val CHANNEL_NAME_PLAYBACK = "Playback"
    const val CHANNEL_DESC_PLAYBACK = "Controls and status for your listening experience."

    const val UPLOAD_NOTIFICATION_ID = 3001

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
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = CHANNEL_DESC_INTERACTIONS
            }
            
            // Create Reminders Channel (IMPORTANCE_LOW for subtlety)
            val remindersChannel = NotificationChannel(
                CHANNEL_ID_REMINDERS,
                CHANNEL_NAME_REMINDERS,
                NotificationManager.IMPORTANCE_LOW,
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
                setShowBadge(false) // Uploads don't need badges
            }

            // Create Playback Channel (IMPORTANCE_LOW - non-interruptive updates)
            val playbackChannel = NotificationChannel(
                CHANNEL_ID_PLAYBACK,
                CHANNEL_NAME_PLAYBACK,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC_PLAYBACK
                setShowBadge(false)
            }
            
            notificationManager.createNotificationChannels(
                listOf(interactionsChannel, remindersChannel, uploadsChannel, playbackChannel)
            )
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
}
