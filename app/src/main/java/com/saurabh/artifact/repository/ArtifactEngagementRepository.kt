package com.saurabh.artifact.repository

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.data.local.SuppressedArtifactDao
import com.saurabh.artifact.data.local.SuppressedArtifactEntity
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.FeedbackType
import com.saurabh.artifact.service.PersonalizationEngine
import com.saurabh.artifact.worker.InteractionSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles all user engagement, playback recording, and personalization signals.
 * This repository owns the interaction data that drives the Artifact personalization loop.
 */
@Singleton
class ArtifactEngagementRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val suppressedArtifactDao: dagger.Lazy<SuppressedArtifactDao>,
    private val pendingInteractionDao: dagger.Lazy<PendingInteractionDao>,
    private val personalizationEngine: dagger.Lazy<PersonalizationEngine>,
    private val settingsRepository: dagger.Lazy<SettingsRepository>,
    private val diagnosticLogger: DiagnosticLogger,
    private val feedRepository: dagger.Lazy<FeedRepository>? = null
) {

    constructor(
        firestore: FirebaseFirestore,
        personalizationEngine: dagger.Lazy<PersonalizationEngine>,
        settingsRepository: dagger.Lazy<SettingsRepository>,
        diagnosticLogger: DiagnosticLogger
    ) : this(
        context = firestore.app.applicationContext,
        firestore = firestore,
        suppressedArtifactDao = dagger.Lazy { error("SuppressedArtifactDao not provided") },
        pendingInteractionDao = dagger.Lazy { error("PendingInteractionDao not provided") },
        personalizationEngine = personalizationEngine,
        settingsRepository = settingsRepository,
        diagnosticLogger = diagnosticLogger,
        feedRepository = null
    )

    /**
     * Records a playback event for an artifact.
     * Updates local personalization state and remote emotion preferences if consent is given.
     */
    suspend fun recordPlay(userId: String?, artifactId: String, emotion: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (emotion.isEmpty()) return@withContext Result.success(Unit)
        
        try {
            ArtifactLogger.d(DiagnosticCategory.RESONANCE, "PLAY_RECORD_STARTED", mapOf(LogKeys.ARTIFACT_ID to artifactId))
            
            val hasConsent = settingsRepository.get().userSettings.first().dataCollectionConsent

            if (hasConsent) {
                personalizationEngine.get().recordInteraction(emotion)
            }

            if (userId == null || !hasConsent) return@withContext Result.success(Unit)

            val settingsRef = firestore.collection("users").document(userId)
                .collection("private").document("settings")
            
            firestore.runTransaction { transaction ->
                val settingsDoc = transaction[settingsRef]
                if (settingsDoc.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val currentPrefs = settingsDoc["emotionPreferences"] as? Map<String, Long> ?: emptyMap()
                    val newCount = (currentPrefs[emotion] ?: 0L) + 1
                    val newPrefs = currentPrefs.toMutableMap().apply { put(emotion, newCount) }
                    
                    transaction.update(settingsRef, mapOf(
                        "emotionPreferences" to newPrefs,
                        "lastActivityTimestamp" to FieldValue.serverTimestamp()
                    ))

                    val newDominant = newPrefs.maxByOrNull { it.value }?.key
                    if (newDominant != null) {
                        val userRef = firestore.collection("users").document(userId)
                        transaction.update(userRef, "dominantEmotion", newDominant)
                    }
                }
            }.await()
            
            ArtifactLogger.i(DiagnosticCategory.RESONANCE, "PLAY_RECORD_SUCCESS", mapOf(LogKeys.ARTIFACT_ID to artifactId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.RESONANCE, "PLAY_RECORD_FAILED", mapOf("emotion" to emotion, LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Submits private feedback that is hidden from the public and the author.
     * For FeedbackType.NOT_FOR_ME, persists locally to SuppressedArtifactDao and enqueues
     * a pending interaction for reliable background synchronization.
     */
    suspend fun submitPrivateFeedback(
        artifactId: String,
        userId: String,
        type: FeedbackType
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            ArtifactLogger.i(DiagnosticCategory.FIRESTORE, "PRIVATE_FEEDBACK_STARTED", mapOf(LogKeys.ARTIFACT_ID to artifactId, "type" to type.name))
            
            if (type == FeedbackType.NOT_FOR_ME) {
                // 1. Immediately persist locally to Room SuppressedArtifactDao
                try {
                    suppressedArtifactDao.get().insert(
                        SuppressedArtifactEntity(
                            userId = userId,
                            artifactId = artifactId,
                            suppressedAt = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    ArtifactLogger.w(DiagnosticCategory.FIRESTORE, "SUPPRESSED_ARTIFACT_LOCAL_INSERT_SKIPPED", mapOf("error" to (e.message ?: "")))
                }

                // 2. Atomically replace any existing pending interaction for this artifact
                try {
                    val pending = PendingInteractionEntity(
                        userId = userId,
                        artifactId = artifactId,
                        interactionType = InteractionType.NOT_FOR_ME,
                        action = InteractionAction.ADD,
                        metadata = FeedbackType.NOT_FOR_ME.name
                    )
                    pendingInteractionDao.get().replacePendingInteraction(pending)
                    InteractionSyncWorker.enqueue(context)
                } catch (e: Exception) {
                    ArtifactLogger.w(DiagnosticCategory.FIRESTORE, "SUPPRESSED_ARTIFACT_QUEUE_SKIPPED", mapOf("error" to (e.message ?: "")))
                }

                // 3. Clear discovery candidate buffer
                feedRepository?.get()?.clearDiscoveryBuffer()

                // 4. Local re-ranking if consent is given
                val hasConsent = settingsRepository.get().userSettings.first().dataCollectionConsent
                if (hasConsent) {
                    val artifact = firestore.collection("artifacts").document(artifactId).get().await()
                    val emotion = artifact.getString("emotion") ?: ""
                    if (emotion.isNotEmpty()) {
                        personalizationEngine.get().recordInteraction(emotion, weight = -1.0f)
                    }
                }
            } else {
                val feedbackId = "${userId}_${artifactId}_${type.name}"
                val feedbackRef = firestore.collection("feedback_private").document(feedbackId)

                firestore.runTransaction { transaction ->
                    val feedbackData = mapOf(
                        "userId" to userId,
                        "artifactId" to artifactId,
                        "type" to type.name,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    transaction[feedbackRef] = feedbackData
                }.await()
            }

            ArtifactLogger.i(DiagnosticCategory.FIRESTORE, "PRIVATE_FEEDBACK_SUCCESS", mapOf(LogKeys.ARTIFACT_ID to artifactId, "type" to type.name))
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

    /**
     * Outbound synchronization method for artifact suppression.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Performs direct write to users/{userId}/private/suppressions/artifacts/{artifactId}.
     */
    suspend fun syncSuppressionToFirestore(userId: String, artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (authUid == null || authUid != userId) {
            return@withContext Result.failure(IllegalStateException("Unauthenticated or user mismatch during suppression sync"))
        }
        try {
            val docRef = firestore.collection("users").document(userId)
                .collection("private").document("suppressions")
                .collection("artifacts").document(artifactId)

            docRef.set(mapOf(
                "userId" to userId,
                "artifactId" to artifactId,
                "type" to "NOT_FOR_ME",
                "suppressedAt" to FieldValue.serverTimestamp()
            )).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Outbound synchronization removal method for artifact suppression.
     * INTERNAL SYNC API: Intended exclusively for InteractionSyncWorker.
     * Deletes users/{userId}/private/suppressions/artifacts/{artifactId}.
     */
    suspend fun syncSuppressionRemovalFromFirestore(userId: String, artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (authUid == null || authUid != userId) {
            return@withContext Result.failure(IllegalStateException("Unauthenticated or user mismatch during suppression removal sync"))
        }
        try {
            firestore.collection("users").document(userId)
                .collection("private").document("suppressions")
                .collection("artifacts").document(artifactId)
                .delete().await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One-shot remote restoration of private suppression records into SuppressedArtifactDao.
     * Scoped strictly to the current authenticated UID.
     * Preserves pending ADD and REMOVE/DELETE operations across reconciliation.
     */
    suspend fun restoreSuppressionRecords(userId: String): Result<Int> = withContext(Dispatchers.IO) {
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (authUid == null || authUid != userId) {
            return@withContext Result.failure(IllegalStateException("Unauthenticated or user mismatch during suppression restoration"))
        }
        try {
            // STEP 1: Capture pending local operations BEFORE fetching remote snapshot
            val pendingBeforeFetch = pendingInteractionDao.get().getPendingForUser(userId)
                .filter { it.interactionType == InteractionType.NOT_FOR_ME }
            val pendingAddBefore = pendingBeforeFetch.filter { it.action == InteractionAction.ADD }.map { it.artifactId }.toSet()
            val pendingRemoveBefore = pendingBeforeFetch.filter { it.action == InteractionAction.REMOVE }.map { it.artifactId }.toSet()

            // STEP 2: Fetch remote Firestore snapshot
            val snapshot = firestore.collection("users").document(userId)
                .collection("private").document("suppressions")
                .collection("artifacts")
                .get().await()

            // STEP 3: Capture pending local operations AFTER fetching remote snapshot as well
            val pendingAfterFetch = pendingInteractionDao.get().getPendingForUser(userId)
                .filter { it.interactionType == InteractionType.NOT_FOR_ME }
            val pendingAddIds = pendingAddBefore + pendingAfterFetch.filter { it.action == InteractionAction.ADD }.map { it.artifactId }.toSet()
            val pendingRemoveIds = pendingRemoveBefore + pendingAfterFetch.filter { it.action == InteractionAction.REMOVE }.map { it.artifactId }.toSet()

            val validRemoteIds = mutableSetOf<String>()
            val entitiesToInsert = mutableListOf<SuppressedArtifactEntity>()

            for (doc in snapshot.documents) {
                val artifactId = doc.id
                val docUserId = doc.getString("userId")
                val type = doc.getString("type")
                if (docUserId == userId && type == "NOT_FOR_ME" && artifactId.isNotEmpty()) {
                    if (artifactId !in pendingRemoveIds) {
                        validRemoteIds.add(artifactId)
                        val suppressedAt = doc.getTimestamp("suppressedAt")?.toDate()?.time ?: System.currentTimeMillis()
                        entitiesToInsert.add(
                            SuppressedArtifactEntity(
                                userId = userId,
                                artifactId = artifactId,
                                suppressedAt = suppressedAt
                            )
                        )
                    }
                }
            }

            for (entity in entitiesToInsert) {
                suppressedArtifactDao.get().insert(entity)
            }

            val localIds = suppressedArtifactDao.get().getSuppressedArtifactIds(userId)
            val toRemoveLocally = localIds.filter { id ->
                (id !in validRemoteIds && id !in pendingAddIds) || (id in pendingRemoveIds)
            }

            for (artifactId in toRemoveLocally) {
                suppressedArtifactDao.get().delete(userId, artifactId)
            }

            feedRepository?.get()?.clearDiscoveryBuffer()

            ArtifactLogger.i(
                DiagnosticCategory.FIRESTORE,
                "SUPPRESSION_RESTORE_SUCCESS",
                mapOf("userId" to userId, "restoredCount" to entitiesToInsert.size, "removedCount" to toRemoveLocally.size)
            )
            Result.success(entitiesToInsert.size)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE,
                "SUPPRESSION_RESTORE_FAILED",
                mapOf("userId" to userId),
                e
            )
            Result.failure(e)
        }
    }
}
