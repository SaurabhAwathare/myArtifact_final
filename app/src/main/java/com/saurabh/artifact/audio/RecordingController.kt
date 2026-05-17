package com.saurabh.artifact.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun startInstantRecording(isComment: Boolean = false) {
        android.util.Log.d("RecordingController", "startInstantRecording(isComment=$isComment)")
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_IS_COMMENT, isComment)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopRecording() {
        android.util.Log.d("RecordingController", "stopRecording()")
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun cancelRecording() {
        android.util.Log.d("RecordingController", "cancelRecording()")
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_CANCEL
        }
        context.startService(intent)
    }

}
