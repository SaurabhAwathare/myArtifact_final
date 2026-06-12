package com.saurabh.artifact.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.saurabh.artifact.MainActivity
import com.saurabh.artifact.R

/**
 * Foundational notification infrastructure for an emotionally intelligent app.
 * Focuses on centralized, reusable, and respectful engagement mechanics.
 */
object NotificationHelper {
    // ... rest of the imports ...

    const val CHANNEL_ID_INTERACTIONS = "interactions_channel"
    const val CHANNEL_NAME_INTERACTIONS = "Resonances"
    const val CHANNEL_DESC_INTERACTIONS = "Quiet notifications for reflections and reactions to your shared artifacts."

    const val CHANNEL_ID_REPLIES = "replies_channel"
    const val CHANNEL_NAME_REPLIES = "New Reflections"
    const val CHANNEL_DESC_REPLIES = "Immediate updates when someone responds to your artifact."

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

            // Create Replies Channel (IMPORTANCE_HIGH for visibility)
            val repliesChannel = NotificationChannel(
                CHANNEL_ID_REPLIES,
                CHANNEL_NAME_REPLIES,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = CHANNEL_DESC_REPLIES
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
                listOf(interactionsChannel, repliesChannel, remindersChannel, uploadsChannel, playbackChannel)
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

    /**
     * Builds a progress notification for uploads.
     */
    fun buildUploadProgressNotification(
        context: Context,
        title: String,
        progress: Int,
        draftId: String? = null
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_UPLOADS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Uploading artifact...")
            .setContentText("$title • $progress% complete")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (draftId != null) {
            val cancelIntent = Intent(context, com.saurabh.artifact.audio.UploadService::class.java).apply {
                action = com.saurabh.artifact.audio.UploadService.ACTION_CANCEL
                putExtra(com.saurabh.artifact.audio.UploadService.EXTRA_DRAFT_ID, draftId)
            }
            val cancelPendingIntent = PendingIntent.getService(
                context, 
                draftId.hashCode(), 
                cancelIntent, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
        }

        return builder.build()
    }

    /**
     * Updates an existing upload progress notification.
     */
    fun updateUploadProgress(context: Context, title: String, progress: Int, draftId: String? = null) {
        if (!hasNotificationPermission(context)) {
            Log.d("NotificationHelper", "Trace: Skipping progress update (no permission)")
            return
        }
        
        Log.d("NotificationHelper", "Trace: Updating upload progress for $title to $progress%")
        val notification = buildUploadProgressNotification(context, title, progress, draftId)
        try {
            NotificationManagerCompat.from(context).notify(UPLOAD_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.e("NotificationHelper", "SecurityException while updating upload progress", e)
        }
    }

    /**
     * Provides ForegroundInfo for WorkManager uploads.
     */
    fun getUploadForegroundInfo(context: Context, title: String, progress: Int): ForegroundInfo {
        val notification = buildUploadProgressNotification(context, title, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UPLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(UPLOAD_NOTIFICATION_ID, notification)
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
        artifactId: String? = null,
        channelId: String = CHANNEL_ID_INTERACTIONS
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

        // Determine priority based on channel
        val priority = if (channelId == CHANNEL_ID_REPLIES) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Standardize to mipmap for compatibility
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(priority)
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
            NotificationManagerCompat.from(context).notify(UPLOAD_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.e("NotificationHelper", "SecurityException while showing upload success notification", e)
        }
    }

    /**
     * Shows a notification when an upload fails.
     */
    fun showUploadErrorNotification(context: Context, title: String) {
        if (!hasNotificationPermission(context)) return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_UPLOADS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Upload Interrupted")
            .setContentText("Something went wrong. \"$title\" is safely saved locally.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(UPLOAD_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.e("NotificationHelper", "SecurityException while showing upload error notification", e)
        }
    }
}
