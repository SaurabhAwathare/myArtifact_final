package com.saurabh.artifact.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.SetOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.saurabh.artifact.domain.review.EngagementEvidence
import com.saurabh.artifact.domain.review.EngagementState
import com.saurabh.artifact.domain.review.UnlockStatus
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
     * Observes the authoritative unlock status from Firestore.
     */
    fun observeRemoteUnlockStatus(userId: String, artifactId: String): Flow<UnlockStatus?> = callbackFlow {
        val traceId = artifactId
        diagnosticLogger.info(
            DiagnosticCategory.FIRESTORE,
            "INVESTIGATION_LOG",
            mapOf(
                "TRACE_ID" to traceId,
                "Stage" to "RepositoryEntry",
                "AuthUID" to (FirebaseAuth.getInstance().currentUser?.uid ?: "null"),
                "PathUID" to userId,
                "ArtifactId" to artifactId,
                "Thread" to Thread.currentThread().name,
                "Timestamp" to System.currentTimeMillis()
            )
        )

        val docRef = firestore.collection("users").document(userId)
            .collection("engagement").document(artifactId)

        diagnosticLogger.info(
            DiagnosticCategory.FIRESTORE,
            "INVESTIGATION_LOG",
            mapOf(
                "TRACE_ID" to traceId,
                "Stage" to "ListenerAttach",
                "AuthUID" to (FirebaseAuth.getInstance().currentUser?.uid ?: "null"),
                "PathUID" to userId,
                "ArtifactId" to artifactId,
                "DocumentPath" to docRef.path,
                "RuleTarget" to "users/{uid}/engagement/{artifactId}",
                "Thread" to Thread.currentThread().name,
                "Timestamp" to System.currentTimeMillis()
            )
        )

        val listener = docRef.addSnapshotListener { snapshot, error ->
            diagnosticLogger.info(
                DiagnosticCategory.FIRESTORE,
                "INVESTIGATION_LOG",
                mapOf(
                    "TRACE_ID" to traceId,
                    "Stage" to "ListenerCallback",
                    "AuthUID" to (FirebaseAuth.getInstance().currentUser?.uid ?: "null"),
                    "PathUID" to userId,
                    "ArtifactId" to artifactId,
                    "Success" to (error == null),
                    "ErrorCode" to (error?.code?.name ?: "NONE"),
                    "ErrorMessage" to (error?.message ?: "NONE"),
                    "SnapshotExists" to (snapshot?.exists() ?: false),
                    "Thread" to Thread.currentThread().name,
                    "Timestamp" to System.currentTimeMillis()
                )
            )

            if (error != null) {
                diagnosticLogger.error(
                    DiagnosticCategory.FIRESTORE,
                    "UNLOCK_OBSERVATION_FAILED",
                    mapOf("artifactId" to artifactId, "userId" to userId),
                    error
                )
                close(error)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val status = UnlockStatus(
                    isCommentUnlocked = snapshot.getBoolean("isCommentUnlocked") ?: false,
                    unlockTimestamp = snapshot.getTimestamp("unlockTimestamp")?.toDate()?.time,
                    engagementState = EngagementState.fromString(snapshot.getString("engagementState")),
                    unlockReason = snapshot.getString("unlockReason"),
                    updatedAt = snapshot.getTimestamp("updatedAt")?.toDate()?.time
                )
                trySend(status)
            } else {
                trySend(null)
            }
        }

        awaitClose { listener.remove() }
    }

    /**
     * Uploads listening evidence to Firestore.
     * Path: users/{userId}/engagement/{artifactId}
     * 
     * ARCHITECTURAL INVARIANT: This method must NEVER write to 'isCommentUnlocked' 
     * or other fields reserved for backend authority.
     */
    suspend fun uploadEngagement(userId: String, evidence: EngagementEvidence): Result<Unit> = withContext(Dispatchers.IO) {
        val traceId = evidence.artifactId
        diagnosticLogger.info(
            DiagnosticCategory.FIRESTORE,
            "INVESTIGATION_LOG",
            mapOf(
                "TRACE_ID" to traceId,
                "Stage" to "RepositoryEntry",
                "AuthUID" to (FirebaseAuth.getInstance().currentUser?.uid ?: "null"),
                "PathUID" to userId,
                "ArtifactId" to evidence.artifactId,
                "Thread" to Thread.currentThread().name,
                "Timestamp" to System.currentTimeMillis()
            )
        )

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

            diagnosticLogger.info(
                DiagnosticCategory.FIRESTORE,
                "INVESTIGATION_LOG",
                mapOf(
                    "TRACE_ID" to traceId,
                    "Stage" to "WriteRequest",
                    "AuthUID" to (FirebaseAuth.getInstance().currentUser?.uid ?: "null"),
                    "PathUID" to userId,
                    "ArtifactId" to evidence.artifactId,
                    "DocumentPath" to docRef.path,
                    "RuleTarget" to "users/{uid}/engagement/{artifactId}",
                    "OperationMode" to "UPSERT_MERGE",
                    "PayloadKeys" to data.keys.toList(),
                    "Thread" to Thread.currentThread().name,
                    "Timestamp" to System.currentTimeMillis()
                )
            )

            // Use merge to avoid overwriting backend-managed fields like isCommentUnlocked
            docRef.set(data, SetOptions.merge()).await()

            diagnosticLogger.info(
                DiagnosticCategory.FIRESTORE,
                "INVESTIGATION_LOG",
                mapOf(
                    "TRACE_ID" to traceId,
                    "Stage" to "WriteResult",
                    "AuthUID" to (FirebaseAuth.getInstance().currentUser?.uid ?: "null"),
                    "PathUID" to userId,
                    "ArtifactId" to evidence.artifactId,
                    "Result" to "SUCCESS",
                    "Thread" to Thread.currentThread().name,
                    "Timestamp" to System.currentTimeMillis()
                )
            )

            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.info(
                DiagnosticCategory.FIRESTORE,
                "INVESTIGATION_LOG",
                mapOf(
                    "TRACE_ID" to traceId,
                    "Stage" to "WriteResult",
                    "AuthUID" to (FirebaseAuth.getInstance().currentUser?.uid ?: "null"),
                    "PathUID" to userId,
                    "ArtifactId" to evidence.artifactId,
                    "Result" to "FAILURE",
                    "ErrorCode" to ((e as? FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"),
                    "ErrorMessage" to (e.message ?: "NONE"),
                    "Thread" to Thread.currentThread().name,
                    "Timestamp" to System.currentTimeMillis()
                )
            )
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
