package com.saurabh.artifact.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saurabh.artifact.util.NotificationEngine
import com.saurabh.artifact.util.NotificationHelper

import androidx.hilt.work.HiltWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * A periodic worker that sends gentle, daily reflection reminders.
 * Part of the emotionally intelligent engagement infrastructure.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // 1. Retrieve emotional and safety context (Mocked for now)
        val emotion = getUserDominantEmotion()
        val safetyLevel = getSafetyLevel()

        // 2. Generate personalized, empathetic copy
        val (title, message) = NotificationEngine.generateMessage(
            emotion = emotion,
            safetyLevel = safetyLevel
        )

        // 3. Show the non-intrusive reminder
        NotificationHelper.showReminderNotification(applicationContext, title, message)
        
        return Result.success()
    }

    /**
     * Placeholder: In production, this will pull from the EmotionProfiler/Repository.
     */
    private fun getUserDominantEmotion(): String {
        return listOf("Anxiety", "Sadness", "Joy", "Anger", "Peace").random()
    }

    /**
     * Placeholder: In production, this will pull from the SafetyEvaluator.
     */
    private fun getSafetyLevel(): String {
        // Simulate rare high-risk detection for testing override
        return if (Math.random() < 0.1) "HIGH" else "LOW"
    }
}
