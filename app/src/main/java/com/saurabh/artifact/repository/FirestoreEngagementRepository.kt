package com.saurabh.artifact.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.SetOptions
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreEngagementRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val diagnosticLogger: DiagnosticLogger
) {
    /**
     * Uploads listening evidence to Firestore.
     * Path: users/{userId}/engagement/{artifactId}
     * 
     * ARCHITECTURAL INVARIANT: This method must NEVER write to 'isCommentUnlocked' 
     * or other fields reserved for backend authority.
     */
    suspend fun uploadEngagement(userId: String, evidence: EngagementEvidence): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = firestore.collection("users").document(userId)
                .collection("engagement").document(evidence.artifactId)

            val data = mapOf(
                "artifactId" to evidence.artifactId,
                "userId" to userId,
                "version" to evidence.versionTag,
                "totalDurationMs" to evidence.durationMs,
                "audioChecksum" to evidence.audioChecksum,
                "coverage" to Blob.fromBytes(evidence.coverage.toByteArray()),
                "lastPositionMs" to evidence.lastPositionMs,
                "furthestPositionMs" to evidence.furthestPositionMs,
                "hasReachedEnd" to evidence.hasReachedEnd,
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            // Use merge to avoid overwriting backend-managed fields like isCommentUnlocked
            docRef.set(data, SetOptions.merge()).await()

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE,
                "ENGAGEMENT_UPLOAD_FAILED",
                mapOf("artifactId" to evidence.artifactId, "userId" to userId),
                e
            )
            Result.failure(AppError.from(e))
        }
    }
}
