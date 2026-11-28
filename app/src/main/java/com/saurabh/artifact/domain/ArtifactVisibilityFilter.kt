package com.saurabh.artifact.domain

import androidx.room.withTransaction
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.IgnoredUserDao
import com.saurabh.artifact.data.local.IgnoredUserEntity
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.ReportedArtifactDao
import com.saurabh.artifact.data.local.ReportedArtifactEntity
import com.saurabh.artifact.data.local.SuppressedArtifactDao
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A centralized policy engine for determining artifact visibility.
 * This service unifies local suppression rules (like reporting, NOT_FOR_ME, and ignored users) across all discovery surfaces.
 * Acts as the bridge for cross-device report and ignore synchronization.
 */
@Singleton
class ArtifactVisibilityFilter @Inject constructor(
    private val reportedArtifactDao: Lazy<ReportedArtifactDao>,
    private val ignoredUserDao: Lazy<IgnoredUserDao>,
    private val firestore: FirebaseFirestore,
    private val pendingInteractionDao: Lazy<PendingInteractionDao> = Lazy { error("PendingInteractionDao not provided") },
    private val appDatabase: Lazy<AppDatabase> = Lazy { error("AppDatabase not provided") },
    private val suppressedArtifactDao: Lazy<SuppressedArtifactDao> = Lazy { error("SuppressedArtifactDao not provided") },
    private val userProfileManager: Lazy<com.saurabh.artifact.repository.UserProfileManager> = Lazy { error("UserProfileManager not provided") },
    private val userSessionManager: Lazy<com.saurabh.artifact.data.local.UserSessionManager> = Lazy { error("UserSessionManager not provided") }
) {
    /**
     * Fetches a one-shot snapshot of suppressed artifact IDs from Room.
     * Combines both reported_artifacts and suppressed_artifacts (NOT_FOR_ME).
     */
    suspend fun getSuppressedIdsSnapshot(userId: String): Set<String> {
        val reported = reportedArtifactDao.get().getReportedArtifactIds(userId)
        val suppressed = suppressedArtifactDao.get().getSuppressedArtifactIds(userId)
        return (reported + suppressed).toSet()
    }

    /**
     * Checks if local suppression records exist for [userId] in Room.
     */
    suspend fun hasLocalSuppressionRecords(userId: String): Boolean {
        if (userId.isEmpty()) return false
        val reported = reportedArtifactDao.get().getReportedArtifactIds(userId)
        val suppressed = suppressedArtifactDao.get().getSuppressedArtifactIds(userId)
        return reported.isNotEmpty() || suppressed.isNotEmpty()
    }

    /**
     * Synchronously evaluates if suppression restoration is ready to authorize feed presentation for [userId].
     */
    suspend fun isSuppressionRestorationReady(userId: String): Boolean {
        if (userId.isEmpty()) return false

        if (hasLocalSuppressionRecords(userId)) {
            return true
        }

        val manager = runCatching { userProfileManager.get() }.getOrNull()
        val restorationState = manager?.suppressionRestorationState?.value
        if (restorationState is com.saurabh.artifact.repository.SuppressionRestorationState.Completed && restorationState.userId == userId) {
            return true
        }

        val sessionMgr = runCatching { userSessionManager.get() }.getOrNull()
        if (sessionMgr?.isSuppressionRestorationCompleted(userId) == true) {
            return true
        }

        return false
    }

    /**
     * Provides a reactive stream indicating whether suppression restoration is ready for [userId].
     */
    fun observeSuppressionRestorationReady(userId: String): Flow<Boolean> {
        if (userId.isEmpty()) return kotlinx.coroutines.flow.flowOf(false)

        val manager = runCatching { userProfileManager.get() }.getOrNull()
        val sessionMgr = runCatching { userSessionManager.get() }.getOrNull()

        val restorationStateFlow: Flow<com.saurabh.artifact.repository.SuppressionRestorationState> = manager?.suppressionRestorationState ?: kotlinx.coroutines.flow.flowOf(com.saurabh.artifact.repository.SuppressionRestorationState.Idle)
        val durableFlow: Flow<Boolean> = sessionMgr?.observeSuppressionRestorationCompleted(userId) ?: kotlinx.coroutines.flow.flowOf(false)

        return combine(
            observeSuppressedIds(userId),
            observeIgnoredUserIds(userId),
            restorationStateFlow,
            durableFlow
        ) { suppressed, _, restorationState, isDurable ->
            if (suppressed.isNotEmpty()) {
                true
            } else if (restorationState is com.saurabh.artifact.repository.SuppressionRestorationState.Completed && restorationState.userId == userId) {
                true
            } else if (isDurable) {
                true
            } else {
                false
            }
        }.map { it }
    }

    /**
     * Provides a reactive stream of suppressed artifact IDs from Room.
     * Combines both reported_artifacts and suppressed_artifacts (NOT_FOR_ME).
     */
    fun observeSuppressedIds(userId: String): Flow<Set<String>> {
        return combine(
            reportedArtifactDao.get().observeReportedArtifactIds(userId),
            suppressedArtifactDao.get().observeSuppressedArtifactIds(userId)
        ) { reported, suppressed ->
            (reported + suppressed).toSet()
        }
    }

    /**
     * Fetches a one-shot snapshot of ignored user IDs from Room.
     */
    suspend fun getIgnoredUserIdsSnapshot(ownerUserId: String): Set<String> {
        return ignoredUserDao.get().getAllIgnoredUserIds(ownerUserId).toSet()
    }

    /**
     * Provides a reactive stream of ignored user IDs from Room.
     */
    fun observeIgnoredUserIds(ownerUserId: String): Flow<Set<String>> {
        return ignoredUserDao.get().observeAllIgnoredUserIds(ownerUserId).map { it.toSet() }
    }

    /**
     * Synchronizes private report markers from Firestore to the local Room database.
     * This provides cross-device persistence for user-specific reports.
     */
    fun syncReportsFromRemote(userId: String, scope: CoroutineScope): Flow<Unit> = callbackFlow {
        if (userId.isEmpty()) {
            trySend(Unit)
            close()
            return@callbackFlow
        }

        // Path: users/{uid}/private/reports/artifacts/{artifactId}
        val collectionRef = firestore.collection("users").document(userId)
            .collection("private").document("reports")
            .collection("artifacts")
        
        val registration = collectionRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                ArtifactLogger.w(
                    DiagnosticCategory.AUTH,
                    "SYNC_REPORTS_LISTENER_ERROR",
                    mapOf("userId" to userId, "code" to error.code.name, "message" to (error.message ?: "")),
                    error
                )
                trySend(Unit)
                close()
                return@addSnapshotListener
            }
            
            if (snapshot != null) {
                scope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val artifactId = change.document.id
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                reportedArtifactDao.get().insert(ReportedArtifactEntity(userId, artifactId))
                            }
                            DocumentChange.Type.REMOVED -> {
                                reportedArtifactDao.get().delete(userId, artifactId)
                            }
                        }
                    }
                    trySend(Unit)
                }
            }
        }
        awaitClose { registration.remove() }
    }

    /**
     * Synchronizes the user's private ignore list from Firestore to Room.
     * Protects local pending IGNORE (ADD) and UNIGNORE (REMOVE) operations from conflicting remote snapshot events.
     * Resilient against listener errors without permanently closing the stream.
     */
    fun syncIgnoredUsersFromRemote(userId: String, scope: CoroutineScope): Flow<Unit> = callbackFlow {
        if (userId.isEmpty()) {
            trySend(Unit)
            close()
            return@callbackFlow
        }

        // Path: users/{uid}/private/ignored_users/users/{targetUid}
        val collectionRef = firestore.collection("users").document(userId)
            .collection("private").document("ignored_users")
            .collection("users")

        var registration: ListenerRegistration? = null
        var retryJob: Job? = null
        var retryDelayMs = 1000L

        fun attachListener() {
            if (!scope.isActive) return
            registration?.remove()
            registration = collectionRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    ArtifactLogger.w(
                        DiagnosticCategory.AUTH,
                        "SYNC_IGNORED_USERS_LISTENER_ERROR",
                        mapOf("userId" to userId, "code" to error.code.name, "message" to (error.message ?: "")),
                        error
                    )
                    trySend(Unit)
                    registration?.remove()
                    registration = null

                    // Error recovery: keep stream alive and reconnect with exponential backoff
                    if (scope.isActive) {
                        retryJob?.cancel()
                        retryJob = scope.launch {
                            delay(retryDelayMs)
                            retryDelayMs = minOf(retryDelayMs * 2, 15000L)
                            attachListener()
                        }
                    }
                    return@addSnapshotListener
                }

                // Reset backoff on successful snapshot delivery
                retryDelayMs = 1000L
                if (snapshot != null) {
                    scope.launch {
                        processIgnoredUserSnapshotChanges(userId, snapshot.documentChanges)
                        trySend(Unit)
                    }
                }
            }
        }

        attachListener()

        awaitClose {
            retryJob?.cancel()
            registration?.remove()
        }
    }

    /**
     * Processes inbound Firestore snapshot changes atomically within Room transactions.
     * Safeguard: Local pending IGNORE (ADD) and UNIGNORE (REMOVE) operations take precedence over
     * conflicting remote snapshots to prevent offline/in-flight local user actions from being overwritten.
     */
    internal suspend fun processIgnoredUserSnapshotChanges(userId: String, changes: List<DocumentChange>) {
        if (changes.isEmpty()) return
        try {
            appDatabase.get().withTransaction {
                changes.forEach { change ->
                    val targetUid = change.document.id
                    if (targetUid.isBlank()) return@forEach

                    val pendingList = pendingInteractionDao.get().getPendingByType(
                        artifactId = targetUid,
                        userId = userId,
                        type = InteractionType.IGNORE
                    )
                    val latestPending = pendingList.lastOrNull()

                    when (change.type) {
                        DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                            if (latestPending != null && latestPending.action == InteractionAction.REMOVE) {
                                ArtifactLogger.i(
                                    DiagnosticCategory.SYNC,
                                    "INBOUND_IGNORE_SNAPSHOT_SUPPRESSED",
                                    mapOf(
                                        "userId" to userId,
                                        "targetUid" to targetUid,
                                        "reason" to "Pending local UNIGNORE (REMOVE) takes precedence"
                                    )
                                )
                            } else {
                                ignoredUserDao.get().insert(IgnoredUserEntity(ownerUserId = userId, userId = targetUid))
                            }
                        }
                        DocumentChange.Type.REMOVED -> {
                            if (latestPending != null && latestPending.action == InteractionAction.ADD) {
                                ArtifactLogger.i(
                                    DiagnosticCategory.SYNC,
                                    "INBOUND_UNIGNORE_SNAPSHOT_SUPPRESSED",
                                    mapOf(
                                        "userId" to userId,
                                        "targetUid" to targetUid,
                                        "reason" to "Pending local IGNORE (ADD) takes precedence"
                                    )
                                )
                            } else {
                                ignoredUserDao.get().delete(targetUid, userId)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ArtifactLogger.e(
                DiagnosticCategory.SYNC,
                "PROCESS_IGNORED_SNAPSHOT_FAILED",
                mapOf("userId" to userId),
                e
            )
        }
    }
}
