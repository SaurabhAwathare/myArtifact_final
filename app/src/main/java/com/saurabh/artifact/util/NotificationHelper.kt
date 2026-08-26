package com.saurabh.artifact.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.saurabh.artifact.MainActivity
import com.saurabh.artifact.R
import com.saurabh.artifact.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Foundational notification infrastructure for an emotionally intelligent app.
 * Focuses on centralized, reusable, and respectful engagement mechanics.
 */
object NotificationHelper {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotificationHelperEntryPoint {
        fun settingsRepository(): SettingsRepository
    }

    private fun isNotificationEnabled(context: Context): Boolean {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(context, NotificationHelperEntryPoint::class.java)
            val settingsRepository = entryPoint.settingsRepository()
            // Preference check - use runBlocking sparingly for this short DataStore read
            runBlocking { settingsRepository.userSettings.first().notificationsEnabled }
        } catch (_: Exception) {
            true // Fallback to enabled if repository access fails
        }
    }

    const val CHANNEL_ID_INTERACTIONS = "interactions_channel"
    const val CHANNEL_NAME_INTERACTIONS = "Resonances"
    const val CHANNEL_DESC_INTERACTIONS = "Quiet notifications for reactions to your shared artifacts."

    const val CHANNEL_ID_REMINDERS = "reminders_channel"
    const val CHANNEL_NAME_REMINDERS = "Reminders"
    const val CHANNEL_DESC_REMINDERS = "Gentle nudges to help you stay connected with your feelings."

    const val CHANNEL_ID_UPLOADS = "uploads_channel"
    const val CHANNEL_NAME_UPLOADS = "Upload Status"
    const val CHANNEL_DESC_UPLOADS = "Status updates for your artifact uploads."

    const val CHANNEL_ID_EXPORTS = "exports_channel"
    const val CHANNEL_NAME_EXPORTS = "Data Export"
    const val CHANNEL_DESC_EXPORTS = "Progress and results for your personal data exports."

    const val CHANNEL_ID_PLAYBACK = "playback_channel"
    const val CHANNEL_NAME_PLAYBACK = "Playback"
    const val CHANNEL_DESC_PLAYBACK = "Controls and status for your listening experience."

    const val UPLOAD_NOTIFICATION_ID = 3001
    const val REMINDER_NOTIFICATION_ID = 4001

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

            // Create Exports Channel (IMPORTANCE_DEFAULT to ensure visibility during long-running tasks)
            val exportsChannel = NotificationChannel(
                CHANNEL_ID_EXPORTS,
                CHANNEL_NAME_EXPORTS,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESC_EXPORTS
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
                listOf(interactionsChannel, remindersChannel, uploadsChannel, exportsChannel, playbackChannel)
            )
        }
    }

    const val EXPORT_NOTIFICATION_ID = 5001

    fun buildExportProgressNotification(
        context: Context,
        statusText: String,
        isIndeterminate: Boolean = true,
        progress: Int = 0,
        cancelIntent: PendingIntent? = null
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID_EXPORTS)
            .setContentTitle("Exporting your Artifacts")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(100, progress, isIndeterminate)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                cancelIntent?.let {
                    addAction(R.mipmap.ic_launcher, "Cancel", it)
                }
            }
            .build()
    }

    fun updateExportProgress(
        context: Context,
        statusText: String,
        isIndeterminate: Boolean = true,
        progress: Int = 0,
        cancelIntent: PendingIntent? = null
    ) {
        if (!isNotificationEnabled(context)) return

        try {
            if (hasNotificationPermission(context)) {
                val notification = buildExportProgressNotification(context, statusText, isIndeterminate, progress, cancelIntent)
                NotificationManagerCompat.from(context).notify(EXPORT_NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
            // Permission revoked mid-operation
        }
    }

    fun showExportResultNotification(
        context: Context,
        title: String,
        message: String,
        isSuccess: Boolean,
        fileName: String? = null
    ) {
        if (!isNotificationEnabled(context)) return

        try {
            if (hasNotificationPermission(context)) {
                val finalMessage = if (isSuccess && fileName != null) {
                    "$fileName\n$message"
                } else {
                    message
                }

                val notification = NotificationCompat.Builder(context, CHANNEL_ID_EXPORTS)
                    .setContentTitle(title)
                    .setContentText(finalMessage)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(finalMessage))
                    .setAutoCancel(true)
                    .setPriority(if (isSuccess) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
                    .build()
                
                NotificationManagerCompat.from(context).notify(EXPORT_NOTIFICATION_ID + (if (isSuccess) 1 else 2), notification)
            }
        } catch (_: SecurityException) {
            // Permission revoked
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

    fun buildUploadProgressNotification(
        context: Context,
        title: String,
        progress: Int
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID_UPLOADS)
            .setContentTitle(title)
            .setContentText("Uploading reflection...")
            .setSmallIcon(R.mipmap.ic_launcher) // Placeholder icon
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun updateUploadProgress(
        context: Context,
        title: String,
        progress: Int
    ) {
        if (!isNotificationEnabled(context)) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val notification = buildUploadProgressNotification(context, title, progress)
            NotificationManagerCompat.from(context).notify(UPLOAD_NOTIFICATION_ID, notification)
        }
    }

    fun showUploadSuccessNotification(context: Context, title: String) {
        if (!isNotificationEnabled(context)) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_UPLOADS)
                .setContentTitle("Upload Complete")
                .setContentText("Your reflection \"$title\" has been safely archived.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            
            NotificationManagerCompat.from(context).notify(UPLOAD_NOTIFICATION_ID + 1, notification)
        }
    }

    fun showUploadErrorNotification(context: Context, message: String) {
        if (!isNotificationEnabled(context)) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_UPLOADS)
                .setContentTitle("Upload Failed")
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            
            NotificationManagerCompat.from(context).notify(UPLOAD_NOTIFICATION_ID + 2, notification)
        }
    }

    fun cancelAllNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun showReminderNotification(context: Context, title: String, message: String) {
        if (!isNotificationEnabled(context)) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_REMINDERS)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Shows a notification for social interactions (resonances, comments).
     * Includes artifact navigation when clicked.
     */
    fun showInteractionNotification(
        context: Context,
        title: String,
        message: String,
        artifactId: String?,
        userId: String? = null,
        recipientId: String? = null,
        notificationType: String? = null,
        channelId: String = CHANNEL_ID_INTERACTIONS
    ) {
        if (!isNotificationEnabled(context)) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (artifactId != null) {
                    putExtra("artifactId", artifactId)
                }
                if (userId != null) {
                    putExtra("userId", userId)
                }
                if (recipientId != null) {
                    putExtra("recipientId", recipientId)
                }
                if (notificationType != null) {
                    putExtra("notificationType", notificationType)
                }
            }
            
            // Unique RequestCode to ensure intent data isn't overwritten for multiple notifications
            // If it's a follow notification, use userId for requestCode uniqueness
            val requestCode = artifactId?.hashCode() ?: userId?.hashCode() ?: System.currentTimeMillis().toInt()
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .build()

            NotificationManagerCompat.from(context).notify(requestCode, notification)
        }
    }

    fun getUploadForegroundInfo(context: Context, title: String, progress: Int): ForegroundInfo {
        val notification = buildUploadProgressNotification(context, title, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                UPLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(UPLOAD_NOTIFICATION_ID, notification)
        }
    }
}
