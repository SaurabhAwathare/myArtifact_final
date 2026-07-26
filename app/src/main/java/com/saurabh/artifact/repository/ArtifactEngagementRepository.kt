package com.saurabh.artifact.repository

import androidx.media3.common.util.UnstableApi
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.FeedbackType
import com.saurabh.artifact.service.PersonalizationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles all user engagement, playback recording, and personalization signals.
 * This repository owns the interaction data that drives the Artifact personalization loop.
 */
@OptIn(UnstableApi::class)
@Singleton
class ArtifactEngagementRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val personalizationEngine: dagger.Lazy<PersonalizationEngine>,
    private val settingsRepository: dagger.Lazy<SettingsRepository>,
    private val diagnosticLogger: DiagnosticLogger
) {

    /**
     * Records a playback event for an artifact.
     * Updates local personalization state and remote emotion preferences if consent is given.
     */
    suspend fun recordPlay(userId: String?, artifactId: String, emotion: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (emotion.isEmpty()) return@withContext Result.success(Unit)
        
        try {
            val hasConsent = settingsRepository.get().userSettings.first().dataCollectionConsent

            // 1. Persist locally for immediate personalization (AppSearch) if consent given
            if (hasConsent) {
                personalizationEngine.get().recordInteraction(emotion)
            }

            // 2. Persist to Firestore if authenticated AND consent given
            if (userId == null || !hasConsent) return@withContext Result.success(Unit)
            
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
            
            val playEventId = "play_${userId}_${artifactId}_$today"
            val playEventRef = firestore.collection("artifact_plays").document(playEventId)
            
            // Phase 1: Client logs play event, Cloud Function aggregates
            playEventRef.set(mapOf(
                "userId" to userId,
                "artifactId" to artifactId,
                "timestamp" to FieldValue.serverTimestamp()
            )).await()

            // Update user preferences (legacy/internal signal)
            val userRef = firestore.collection("users").document(userId)
            firestore.runTransaction { transaction ->
                val userDoc = transaction[userRef]
                if (userDoc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val currentPrefs = userDoc["emotionPreferences"] as? Map<String, Long> ?: emptyMap()
                    val newCount = (currentPrefs[emotion] ?: 0L) + 1
                    val newPrefs = currentPrefs.toMutableMap().apply { put(emotion, newCount) }
                    transaction.update(userRef, "emotionPreferences", newPrefs)
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "PLAY_RECORD_FAILED", mapOf("emotion" to emotion, LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Submits private feedback that is hidden from the public and the author.
     * Used for personalization and safety monitoring.
     */
    suspend fun submitPrivateFeedback(
        artifactId: String,
        userId: String,
        type: FeedbackType
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val feedbackId = "${userId}_${artifactId}_${type.name}"
            val feedbackRef = firestore.collection("feedback_private").document(feedbackId)
            val artifactRef = firestore.collection("artifacts").document(artifactId)

            firestore.runTransaction { transaction ->
                val feedbackData = mapOf(
                    "userId" to userId,
                    "artifactId" to artifactId,
                    "type" to type.name,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                transaction[feedbackRef] = feedbackData

                // If it's a safety concern, increment the internal counter
                if (type == FeedbackType.SAFETY_CONCERN) {
                    transaction.update(artifactRef, "safetyConcernCount", FieldValue.increment(1))
                }
            }.await()

            // Trigger local re-ranking if it's "Not for me"
            if (type == FeedbackType.NOT_FOR_ME) {
                val hasConsent = settingsRepository.get().userSettings.first().dataCollectionConsent
                if (hasConsent) {
                    val artifact = firestore.collection("artifacts").document(artifactId).get().await()
                    val emotion = artifact.getString("emotion") ?: ""
                    if (emotion.isNotEmpty()) {
                        personalizationEngine.get().recordInteraction(emotion, weight = -1.0f)
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE, 
                "PRIVATE_FEEDBACK_FAILED", 
                mapOf(LogKeys.ARTIFACT_ID to artifactId, "type" to type.name), 
                e
            )
            Result.failure(e)
        }
    }
}
