package com.saurabh.artifact.repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.auth.FirebaseAuth
import com.saurabh.artifact.data.local.ReportedArtifactDao
import com.saurabh.artifact.data.local.ReportedArtifactEntity
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.ModerationStatus
import com.saurabh.artifact.model.ReportReason
import com.saurabh.artifact.model.ReportStatus
import com.saurabh.artifact.model.UserReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles all moderation, reporting, and administrative safety logic for artifacts.
 * This repository is responsible for the platform's safety compliance and moderation workflows.
 */
@Singleton
class ArtifactModerationRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val reportedArtifactDao: dagger.Lazy<ReportedArtifactDao>,
    private val diagnosticLogger: DiagnosticLogger
) {

    /**
     * Fetches all pending reports from Firestore for administrative review.
     */
    suspend fun getPendingReports(): Result<List<UserReport>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val snapshot = firestore.collection("reports")
                .whereEqualTo("status", ReportStatus.PENDING.name)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .await()
            
            val reports = snapshot.documents.mapNotNull { doc ->
                doc.toObject(UserReport::class.java)?.copy(id = doc.id)
            }
            Result.success(reports)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "FETCH_PENDING_REPORTS_FAILED", throwable = e)
            Result.failure(e)
        }
    }

    /**
     * Resolves a user report by either hiding the artifact or dismissing the report.
     * Uses a transaction to ensure atomic status updates across report and artifact.
     */
    suspend fun resolveReport(
        reportId: String,
        artifactId: String,
        action: ArtifactRepository.ModerationAction
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val reportRef = firestore.collection("reports").document(reportId)
        val artifactRef = firestore.collection("artifacts").document(artifactId)

        return@withContext try {
            firestore.runTransaction { transaction ->
                val status = when (action) {
                    ArtifactRepository.ModerationAction.HIDE_ARTIFACT -> ReportStatus.RESOLVED
                    ArtifactRepository.ModerationAction.DISMISS -> ReportStatus.DISMISSED
                }
                
                transaction.update(reportRef, "status", status.name)
                
                when (action) {
                    ArtifactRepository.ModerationAction.HIDE_ARTIFACT -> {
                        transaction.update(artifactRef, "moderation.status", ModerationStatus.HIDDEN.name)
                        transaction.update(artifactRef, "isPublic", false)
                    }
                    ArtifactRepository.ModerationAction.DISMISS -> { /* Just resolve the report */ }
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE, 
                "REPORT_RESOLVE_FAILED", 
                mapOf(LogKeys.ARTIFACT_ID to artifactId, "reportId" to reportId), 
                e
            )
            Result.failure(e)
        }
    }

    /**
     * Submits a user report for an artifact.
     * Uses device hash for privacy-preserving reporting.
     * Prevents duplicate reports from the same user via deterministic report IDs.
     */
    suspend fun submitReport(
        artifactId: String,
        reason: ReportReason,
        optionalDescription: String,
        deviceIdHash: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val userId = auth.currentUser?.uid 
                ?: return@withContext Result.failure(AppError.Unauthenticated())

            // 1. Deterministic Report ID: {userId}_{artifactId} (Ensures idempotency)
            val reportId = "${userId}_${artifactId}"
            val reportData = mapOf(
                "artifactId" to artifactId,
                "reporterId" to userId,
                "reason" to reason.name,
                "optionalDescription" to optionalDescription,
                "deviceIdHash" to deviceIdHash,
                "createdAt" to FieldValue.serverTimestamp(),
                "status" to ReportStatus.PENDING.name
            )
            
            // 2. Submit the report document (Overwrite if exists ensures retry safety)
            firestore.collection("reports").document(reportId).set(reportData).await()
            
            // 3. Update local Room DB for immediate hiding
            try {
                reportedArtifactDao.get().insert(
                    ReportedArtifactEntity(
                        userId = userId,
                        artifactId = artifactId
                    )
                )
            } catch (e: Exception) {
                diagnosticLogger.error(
                    DiagnosticCategory.DATABASE, 
                    "REPORT_LOCAL_SYNC_FAILED", 
                    mapOf(LogKeys.ARTIFACT_ID to artifactId), 
                    e
                )
                // We don't fail the entire operation if local Room sync fails
            }
                
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(
                DiagnosticCategory.FIRESTORE, 
                "REPORT_SUBMISSION_FAILED", 
                mapOf(LogKeys.ARTIFACT_ID to artifactId), 
                e
            )
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Determines if the current authenticated user has administrative privileges.
     * TODO: Move this to a dedicated AuthorizationService to separate data access from policy.
     */
    suspend fun isCurrentUserAdmin(): Boolean {
        val userId = auth.currentUser?.uid ?: return false
        return try {
            val settingsDoc = firestore.collection("users").document(userId)
                .collection("private").document("settings")
                .get().await()
            settingsDoc.getBoolean("isAdmin") == true
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ADMIN_CHECK_FAILED", throwable = e)
            false
        }
    }

    /**
     * Marks a published artifact as DELETED in Firestore (Soft Delete).
     * This method handles ONLY the remote state change. 
     * Orchestration (local sync, user stats) must be handled by the caller.
     */
    suspend fun softDeleteArtifact(artifactId: String): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val artifactRef = firestore.collection("artifacts").document(artifactId)
            
            firestore.runTransaction { transaction ->
                transaction.update(artifactRef, "status", ArtifactStatus.DELETED.name)
                transaction.update(artifactRef, "isPublic", false)
                transaction.update(artifactRef, "deletedAt", FieldValue.serverTimestamp())
            }.await()
            
            diagnosticLogger.info(DiagnosticCategory.FIRESTORE, "ARTIFACT_REMOTE_SOFT_DELETED", mapOf(LogKeys.ARTIFACT_ID to artifactId))
            Result.success(Unit)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "ARTIFACT_REMOTE_DELETE_FAILED", mapOf(LogKeys.ARTIFACT_ID to artifactId), e)
            Result.failure(e)
        }
    }
}
