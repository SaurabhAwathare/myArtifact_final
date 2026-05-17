package com.saurabh.artifact.audio.distraction

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DistractionManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun getRingerMode(): Int {
        return audioManager.ringerMode
    }

    fun isDndAccessGranted(): Boolean {
        return notificationManager.isNotificationPolicyAccessGranted
    }

    fun enableDnd() {
        if (isDndAccessGranted()) {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
    }

    fun disableDnd() {
        if (isDndAccessGranted()) {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    fun isSilentOrVibrate(): Boolean {
        val mode = getRingerMode()
        return mode == AudioManager.RINGER_MODE_SILENT || mode == AudioManager.RINGER_MODE_VIBRATE
    }
}
