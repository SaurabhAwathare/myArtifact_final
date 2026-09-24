package com.saurabh.artifact.domain.auth

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseUser
import com.saurabh.artifact.audio.MediaCache
import com.saurabh.artifact.audio.MediaPreCacher
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackService
import com.saurabh.artifact.audio.PlaybackSettingsDataStore
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.audio.UploadService
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.SessionManager
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.security.ExportService
import com.saurabh.artifact.service.PersonalizationEngine
import com.saurabh.artifact.util.NotificationHelper
import com.saurabh.artifact.util.OnboardingManager
import com.saurabh.artifact.util.StorageManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class LogoutCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: UserSessionManager,
    private val playbackCoordinator: PlaybackCoordinator,
    private val playbackSettingsDataStore: PlaybackSettingsDataStore,
    private val recordingSessionManager: RecordingSessionManager,
    private val workManager: WorkManager,
    private val database: Lazy<AppDatabase>,
    private val storageManager: StorageManager,
    private val backupEncryptionManager: BackupEncryptionManager,
    private val onboardingManager: OnboardingManager,
    private val databaseEncryptionManager: DatabaseEncryptionManager,
    private val personalizationEngine: Lazy<PersonalizationEngine>,
    private val diagnosticLogger: DiagnosticLogger,
    private val diagnosticSessionManager: SessionManager? = null,
) {

    // Dispatchers can be overridden for testing
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    private val stateMutex = Mutex()

    private val activeOperations = mutableMapOf<CleanupEventKey, Deferred<CleanupResult>>()
    private val activePhaseEOperations = mutableMapOf<CleanupEventKey, Deferred<Result<Unit>>>()

    private class BoundedLruMap<K, V>(private val maxSize: Int = 10) :
        LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }

    private val completionRecords = BoundedLruMap<CleanupEventKey, CleanupCompletionRecord>(10)

    suspend fun createEventKey(targetUid: String? = null, sessionInstanceId: String? = null): CleanupEventKey {
        val uid = targetUid
            ?: (authRepository.currentUser.value as? FirebaseUser)?.uid
            ?: authRepository.currentUserId
        
        val sessionId = sessionInstanceId
            ?: try {
                withTimeoutOrNull(200) { sessionManager.localSessionId.first() }
            } catch (_: Exception) {
                null
            }
            ?: "UNKNOWN"

        return CleanupEventKey(targetUid = uid, sessionInstanceId = sessionId)
    }

    /**
     * Executes the comprehensive logout sequence.
     * Hardens the application by clearing all user-specific state and stopping active media,
     * followed by authorized Firebase remote sign-out.
     */
    suspend fun executeLogout(key: CleanupEventKey? = null): Result<CleanupResult> {
        val eventKey = key ?: createEventKey()
        return try {
            val cleanupResult = performFullCleanup(eventKey)

            // Phase E: Remote Session Termination with ownership verification
            val phaseEResult = executePhaseE(eventKey)

            if (phaseEResult.isFailure && cleanupResult.status == CleanupStatus.COMPLETED) {
                diagnosticLogger.warn(
                    DiagnosticCategory.AUTH,
                    "LOGOUT_PHASE_E_FAILED_LOCAL_COMPLETED",
                    mapOf("targetUid" to eventKey.targetUid)
                )
            }

            Result.success(cleanupResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_FATAL_ERROR", throwable = e)
            Result.failure(e)
        }
    }

    /**
     * Clears local user data and stops active media.
     * Executed in deterministic phases: Phase A (Stop), Phase B (Clear State), Phase C (Database), Phase D (Finalize).
     * Deduplicated using immutable CleanupEventKey.
     */
    suspend fun performFullCleanup(key: CleanupEventKey? = null): CleanupResult {
        val cleanupKey = key ?: createEventKey()

        val (existingRecord, activeDeferred) = stateMutex.withLock {
            val record = completionRecords[cleanupKey]
            if (record?.localCleanupCompleted == true) {
                Pair(record, null)
            } else {
                val deferred = activeOperations[cleanupKey]
                Pair(null, deferred)
            }
        }

        if (existingRecord != null) {
            diagnosticLogger.info(
                DiagnosticCategory.AUTH,
                "LOGOUT_CLEANUP_CACHED",
                mapOf("targetUid" to cleanupKey.targetUid, "sessionInstanceId" to cleanupKey.sessionInstanceId)
            )
            return CleanupResult(status = CleanupStatus.COMPLETED)
        }

        if (activeDeferred != null) {
            diagnosticLogger.info(
                DiagnosticCategory.AUTH,
                "LOGOUT_CLEANUP_JOINED",
                mapOf("targetUid" to cleanupKey.targetUid, "sessionInstanceId" to cleanupKey.sessionInstanceId)
            )
            return activeDeferred.await()
        }

        val deferred = CompletableDeferred<CleanupResult>()
        val shouldExecute = stateMutex.withLock {
            if (completionRecords[cleanupKey]?.localCleanupCompleted == true) {
                false
            } else if (activeOperations.containsKey(cleanupKey)) {
                false
            } else {
                activeOperations[cleanupKey] = deferred
                true
            }
        }

        if (!shouldExecute) {
            val inFlight = stateMutex.withLock { activeOperations[cleanupKey] }
            return inFlight?.await() ?: CleanupResult(status = CleanupStatus.COMPLETED)
        }

        try {
            val result = runLocalCleanup(cleanupKey)
            stateMutex.withLock {
                val record = completionRecords[cleanupKey] ?: CleanupCompletionRecord(
                    key = cleanupKey,
                    localCleanupCompleted = true,
                    remoteSignOutCompleted = false
                )
                completionRecords[cleanupKey] = record.copy(localCleanupCompleted = true)
            }
            deferred.complete(result)
            return result
        } catch (e: CancellationException) {
            deferred.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            stateMutex.withLock {
                activeOperations.remove(cleanupKey)
            }
        }
    }

    suspend fun isRemoteSignOutEligible(key: CleanupEventKey): Boolean {
        val currentFirebaseUid = (authRepository.currentUser.value as? FirebaseUser)?.uid
            ?: authRepository.currentUserId.ifEmpty { null }
        val currentLocalSessionId = try {
            withTimeoutOrNull(200) { sessionManager.localSessionId.first() }
        } catch (_: Exception) {
            null
        }

        val isUidMatch = currentFirebaseUid != null && currentFirebaseUid == key.targetUid
        val isSessionMatch = currentLocalSessionId == null || currentLocalSessionId == key.sessionInstanceId

        return isUidMatch && isSessionMatch
    }

    private suspend fun executePhaseE(key: CleanupEventKey): Result<Unit> {
        val (isDone, activeDeferred) = stateMutex.withLock {
            val record = completionRecords[key]
            if (record?.remoteSignOutCompleted == true || record?.isRemoteSignOutSuperseded == true) {
                Pair(true, null)
            } else {
                val deferred = activePhaseEOperations[key]
                Pair(false, deferred)
            }
        }

        if (isDone) {
            return Result.success(Unit)
        }

        if (activeDeferred != null) {
            return activeDeferred.await()
        }

        val deferred = CompletableDeferred<Result<Unit>>()
        val shouldExecute = stateMutex.withLock {
            val record = completionRecords[key]
            if (record?.remoteSignOutCompleted == true || record?.isRemoteSignOutSuperseded == true) {
                false
            } else if (activePhaseEOperations.containsKey(key)) {
                false
            } else {
                activePhaseEOperations[key] = deferred
                true
            }
        }

        if (!shouldExecute) {
            val inFlight = stateMutex.withLock { activePhaseEOperations[key] }
            return inFlight?.await() ?: Result.success(Unit)
        }

        try {
            if (!isRemoteSignOutEligible(key)) {
                diagnosticLogger.info(
                    DiagnosticCategory.AUTH,
                    "LOGOUT_FIREBASE_SKIPPED_SUPERSEDED",
                    mapOf(
                        "targetUid" to key.targetUid,
                        "currentUid" to (authRepository.currentUser.value?.uid ?: "null"),
                        "sessionInstanceId" to key.sessionInstanceId
                    )
                )
                stateMutex.withLock {
                    val record = completionRecords[key] ?: CleanupCompletionRecord(
                        key = key,
                        localCleanupCompleted = true,
                        remoteSignOutCompleted = false
                    )
                    completionRecords[key] = record.copy(isRemoteSignOutSuperseded = true)
                }
                try {
                    sessionManager.setLoggingOut(false)
                } catch (_: Exception) {}
                val res = Result.success(Unit)
                deferred.complete(res)
                return res
            }

            val result = authRepository.signOutAuthorized(
                targetUid = key.targetUid,
                sessionInstanceId = key.sessionInstanceId,
                getLocalSessionId = {
                    try {
                        withTimeoutOrNull(200) { sessionManager.localSessionId.first() }
                    } catch (_: Exception) {
                        null
                    }
                }
            )

            stateMutex.withLock {
                val record = completionRecords[key] ?: CleanupCompletionRecord(
                    key = key,
                    localCleanupCompleted = true,
                    remoteSignOutCompleted = false
                )
                if (result.isSuccess) {
                    completionRecords[key] = record.copy(remoteSignOutCompleted = true)
                    diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_FIREBASE_SUCCESS")
                } else {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_FIREBASE_FAILED", throwable = result.exceptionOrNull())
                }
            }
            deferred.complete(result)
            return result
        } catch (e: CancellationException) {
            deferred.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_FIREBASE_FAILED", throwable = e)
            val failure = Result.failure<Unit>(e)
            deferred.complete(failure)
            return failure
        } finally {
            stateMutex.withLock {
                activePhaseEOperations.remove(key)
            }
        }
    }

    private suspend fun runLocalCleanup(key: CleanupEventKey): CleanupResult = withContext(ioDispatcher) {
        diagnosticLogger.info(
            DiagnosticCategory.AUTH,
            "LOGOUT_CLEANUP_STARTED",
            mapOf("targetUid" to key.targetUid, "sessionInstanceId" to key.sessionInstanceId)
        )

        // R080: Set "Cleanup In Progress" flag early to block dirty restoration on crash/restart
        try {
            sessionManager.setLoggingOut(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.warn(DiagnosticCategory.AUTH, "LOGOUT_FLAG_SET_FAILED", throwable = e)
        }

        var recordingSuccess = true
        var playbackSuccess = true
        var uploadsSuccess = true
        var workersSuccess = true
        var notificationsSuccess = true
        var roomSuccess = true
        var sessionDSSuccess = true
        var settingsDSSuccess = true
        var mediaCacheSuccess = true

        // PHASE A: Stop Active Work (Prevent new IO/writes)
        diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_A")

        // 1. Stop active data export
        try {
            context.stopService(Intent(context, ExportService::class.java))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_EXPORT_FAILED", throwable = e)
        }

        // 2. Stop active recording
        try {
            withContext(mainDispatcher) {
                if (recordingSessionManager.isRecordingActive()) {
                    diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_STOP_RECORDING")
                    recordingSessionManager.cancelSession()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_RECORDING_FAILED", throwable = e)
            recordingSuccess = false
        }

        // 3. Release playback and pre-cache resources
        try {
            withContext(mainDispatcher) {
                playbackCoordinator.release()
                MediaPreCacher.cancelAll()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_PLAYBACK_FAILED", throwable = e)
            playbackSuccess = false
        }

        // 4. Stop background upload service
        try {
            context.stopService(Intent(context, UploadService::class.java))
            delay(200)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_UPLOAD_FAILED", throwable = e)
            uploadsSuccess = false
        }

        // 5. Cancel user-scoped background workers
        try {
            workManager.cancelAllWorkByTag(SessionConstants.TAG_USER_SESSION_WORK).result.await()
            delay(500)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_WORKERS_FAILED", throwable = e)
            workersSuccess = false
        }

        // 6. Cancel notifications
        try {
            NotificationHelper.cancelAllNotifications(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_STOP_NOTIFICATIONS_FAILED", throwable = e)
            notificationsSuccess = false
        }

        // 7. Release Media Cache handles
        try {
            MediaCache.release()
            diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_RELEASE_CACHE_SUCCESS")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_RELEASE_CACHE_FAILED", throwable = e)
            mediaCacheSuccess = false
        }

        // PHASE B: Local Data Cleanup (Database & Files)
        diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_B")

        // 8. Database Cleanup - Clear session tables while preserving artifact_drafts
        try {
            database.get().clearSessionTables()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_DB_FAILED", throwable = e)
            roomSuccess = false
        }

        // 9. User File Cleanup - Clear caches while preserving local Draft files
        try {
            storageManager.clearUserStorage(preserveDrafts = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_STORAGE_FAILED", throwable = e)
        }

        // PHASE C: Clear Local State (DataStores)
        diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_C")

        if (roomSuccess) {
            try {
                sessionManager.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_SESSION_FAILED", throwable = e)
                sessionDSSuccess = false
            }

            try {
                settingsRepository.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_SETTINGS_FAILED", throwable = e)
                settingsDSSuccess = false
            }

            try {
                personalizationEngine.get().clearLocalData()
                diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_PERSONALIZATION_SUCCESS")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_PERSONALIZATION_FAILED", throwable = e)
            }

            try {
                playbackSettingsDataStore.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_PLAYBACK_DS_FAILED", throwable = e)
            }

            try {
                backupEncryptionManager.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_BACKUP_FAILED", throwable = e)
            }

            try {
                onboardingManager.clearUserSessionData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_ONBOARDING_FAILED", throwable = e)
            }

            try {
                databaseEncryptionManager.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_CLEAR_DB_ENCRYPTION_FAILED", throwable = e)
            }
        } else {
            diagnosticLogger.warn(DiagnosticCategory.AUTH, "LOGOUT_SKIP_DATASTORE_CLEAR", mapOf("reason" to "DB cleanup failed"))
            sessionDSSuccess = false
        }

        // PHASE D: Finalize
        diagnosticLogger.debug(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_PHASE_D")
        diagnosticSessionManager?.rotateSession()

        CleanupResult(
            status = CleanupStatus.COMPLETED,
            recording = recordingSuccess,
            playback = playbackSuccess,
            uploads = uploadsSuccess,
            workers = workersSuccess,
            notifications = notificationsSuccess,
            room = roomSuccess,
            sessionDataStore = sessionDSSuccess,
            settingsDataStore = settingsDSSuccess,
            mediaCache = mediaCacheSuccess
        ).also {
            diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_CLEANUP_COMPLETED")
            diagnosticLogger.debug(DiagnosticCategory.AUTH, "SESSION_CLEANUP_COMPLETED")
        }
    }
}
