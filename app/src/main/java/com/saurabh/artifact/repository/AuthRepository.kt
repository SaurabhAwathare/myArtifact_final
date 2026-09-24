package com.saurabh.artifact.repository

import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.User
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Singleton

import com.google.firebase.functions.FirebaseFunctions
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.model.UserPrivateSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

sealed class SessionState {
    object Uninitialized : SessionState()
    object PendingServerVerification : SessionState()
    object Active : SessionState()
    object NoActiveSession : SessionState()
    data class SessionExistsOnOtherDevice(val deviceName: String) : SessionState()
    object Revoked : SessionState()
    data class TokenRefreshFailed(val cause: Throwable) : SessionState()
    data class Unavailable(val cause: Throwable) : SessionState()
}

sealed class ClaimResult {
    object Success : ClaimResult()
    data class SessionExists(val activeDeviceName: String) : ClaimResult()
    data class Failure(val error: Throwable) : ClaimResult()
}

sealed class TransferResult {
    object Success : TransferResult()
    data class Failure(val error: Throwable) : TransferResult()
}

@Singleton
class AuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val credentialManager: CredentialManager,
    private val startupCoordinator: StartupCoordinator,
    private val firebaseFunctions: FirebaseFunctions? = null,
    private val userSessionManager: UserSessionManager? = null,
) {
    val authMutex = Mutex()

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentUser = MutableStateFlow(firebaseAuth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    private val _isRestored = MutableStateFlow(false)
    val isRestored: StateFlow<Boolean> = _isRestored.asStateFlow()

    suspend fun awaitAuthRestoration() {
        _isRestored.first { it }
    }

    private val _userData = MutableStateFlow<User?>(null)
    val userData: StateFlow<User?> = _userData

    private val _privateSettings = MutableStateFlow<com.saurabh.artifact.model.UserPrivateSettings?>(null)
    val privateSettings: StateFlow<com.saurabh.artifact.model.UserPrivateSettings?> = _privateSettings

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Uninitialized)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    companion object {
        private const val MAX_LISTENER_ATTEMPTS = 5
        private const val INITIAL_BACKOFF_MS = 200L
        private const val MAX_BACKOFF_MS = 1000L
    }

    private var userDataListener: ListenerRegistration? = null
    private var userDataListenerId: Int = -1
    private var userDataListenerCreatedAt: Long = 0
    private var userDataRetryJob: Job? = null

    private var privateSettingsListener: ListenerRegistration? = null
    private var privateSettingsRetryJob: Job? = null
    private val activePrivateSettingsListenerGeneration = AtomicInteger(0)
    private val listenerLock = Any()

    private val listenerIdGenerator = java.util.concurrent.atomic.AtomicInteger(0)

    private var lastToken: String? = null

    val currentUserId: String
        get() = firebaseAuth.currentUser?.uid ?: ""

    val currentAnonymousId: String
        get() = userData.value?.anonymousId ?: ""

    init {
        firebaseAuth.addAuthStateListener { auth ->
            val user = auth.currentUser
            ArtifactLogger.i(
                DiagnosticCategory.AUTH, 
                "AUTH_STATE_CHANGED", 
                mapOf("uid" to (user?.uid ?: "null"), "timestamp" to System.currentTimeMillis())
            )
            _currentUser.value = user
            _isRestored.value = true
            if (user != null) {
                repositoryScope.launch {
                    startupCoordinator.awaitComponent(StartupComponent.CORE)
                    android.util.Log.d("RACE_CHECK", "AUTH_LISTENERS_STARTED")
                    observeUserData(user.uid)
                    observePrivateSettings(user.uid)
                }
            } else {
                cleanupListeners()
                _userData.value = null
                _privateSettings.value = null
            }
        }

        firebaseAuth.addIdTokenListener(
            object : FirebaseAuth.IdTokenListener {
            override fun onIdTokenChanged(auth: FirebaseAuth) {
                val user = auth.currentUser
                user?.getIdToken(false)?.addOnSuccessListener { result ->
                    val token = result.token
                    val tokenChanged = token != lastToken
                    lastToken = token

                    ArtifactLogger.i(
                        DiagnosticCategory.AUTH,
                        "ID_TOKEN_METADATA",
                        mapOf(
                            "uid" to (user.uid),
                            "authTime" to result.authTimestamp,
                            "issuedAt" to result.issuedAtTimestamp,
                            "expiration" to result.expirationTimestamp,
                            "signInProvider" to (result.signInProvider ?: "unknown"),
                            "tokenChanged" to tokenChanged,
                            "timestamp" to System.currentTimeMillis()
                        )
                    )
                } ?: run {
                    ArtifactLogger.i(DiagnosticCategory.AUTH, "ID_TOKEN_NULL", mapOf("timestamp" to System.currentTimeMillis()))
                }
            }
        })
    }

    private fun observeUserData(userId: String, attempt: Int = 1) {
        userDataRetryJob?.cancel()
        userDataRetryJob = null

        if (firebaseAuth.currentUser?.uid != userId) {
            ArtifactLogger.w(DiagnosticCategory.AUTH, "USER_DATA_LISTEN_ABORTED_USER_MISMATCH")
            return
        }

        // Prevent duplicate listeners
        if (userDataListener != null) {
            val lifetime = System.currentTimeMillis() - userDataListenerCreatedAt
            ArtifactLogger.i(
                DiagnosticCategory.AUTH,
                "LISTENER_REMOVED",
                mapOf(
                    "path" to "users/$userId",
                    "listenerId" to userDataListenerId,
                    "lifetimeMs" to lifetime,
                    "reason" to "REPLACEMENT"
                )
            )
            userDataListener?.remove()
            userDataListener = null
            ArtifactLogger.i(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "users/$userId"))
        }

        val id = listenerIdGenerator.incrementAndGet()
        val createdAt = System.currentTimeMillis()
        userDataListenerId = id
        userDataListenerCreatedAt = createdAt

        ArtifactLogger.i(
            DiagnosticCategory.AUTH,
            "LISTENER_CREATED",
            mapOf(
                "listenerId" to id,
                "path" to "users/$userId",
                "attempt" to attempt,
                "createdAt" to createdAt,
                "timestamp" to System.currentTimeMillis()
            )
        )

        firebaseAuth.currentUser?.getIdToken(false)

        userDataListener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    ArtifactLogger.e(
                        DiagnosticCategory.AUTH,
                        "SNAPSHOT_CALLBACK_ERROR",
                        mapOf(
                            "listenerId" to id,
                            "path" to "users/$userId",
                            "code" to error.code.name,
                            "message" to (error.message ?: ""),
                            "cause" to (error.cause?.toString() ?: "null"),
                            "attempt" to attempt,
                            "timestamp" to System.currentTimeMillis()
                        ),
                        error
                    )
                    
                    if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        if (firebaseAuth.currentUser?.uid == userId && attempt < MAX_LISTENER_ATTEMPTS) {
                            val calculatedBackoff = INITIAL_BACKOFF_MS * (1 shl (attempt - 1))
                            val backoffMs = calculatedBackoff.coerceAtMost(MAX_BACKOFF_MS)
                            ArtifactLogger.w(
                                DiagnosticCategory.AUTH,
                                "USER_DATA_PERMISSION_DENIED_RETRYING",
                                mapOf("path" to "users/$userId", "attempt" to attempt, "backoffMs" to backoffMs)
                            )
                            userDataRetryJob = repositoryScope.launch {
                                delay(backoffMs)
                                if (firebaseAuth.currentUser?.uid == userId) {
                                    observeUserData(userId, attempt + 1)
                                }
                            }
                        } else {
                            ArtifactLogger.e(
                                DiagnosticCategory.AUTH,
                                "USER_DATA_PERMISSION_DENIED_PERSISTENT",
                                mapOf("path" to "users/$userId", "attempt" to attempt)
                            )
                        }
                    }
                    return@addSnapshotListener
                }

                ArtifactLogger.i(
                    DiagnosticCategory.AUTH,
                    "SNAPSHOT_CALLBACK_SUCCESS",
                    mapOf(
                        "listenerId" to id,
                        "path" to "users/$userId",
                        "exists" to (snapshot?.exists() ?: false),
                        "fromCache" to (snapshot?.metadata?.isFromCache ?: false),
                        "hasPendingWrites" to (snapshot?.metadata?.hasPendingWrites() ?: false),
                        "timestamp" to System.currentTimeMillis()
                    )
                )

                if ((snapshot != null) && snapshot.exists()) {
                    _userData.value = snapshot.toObject(User::class.java)?.copy(id = snapshot.id)
                } else {
                    _userData.value = null
                }
            }
    }

    internal fun observePrivateSettings(userId: String, attempt: Int = 1) {
        privateSettingsRetryJob?.cancel()
        privateSettingsRetryJob = null

        val listenerGen: Int
        synchronized(listenerLock) {
            if (firebaseAuth.currentUser?.uid != userId) {
                ArtifactLogger.w(DiagnosticCategory.AUTH, "PRIVATE_SETTINGS_LISTEN_ABORTED_USER_MISMATCH")
                return
            }

            if (privateSettingsListener != null) {
                privateSettingsListener?.remove()
                privateSettingsListener = null
                ArtifactLogger.i(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "users/$userId/private/settings"))
            }

            listenerGen = activePrivateSettingsListenerGeneration.incrementAndGet()
            _privateSettings.value = null
            _sessionState.value = SessionState.PendingServerVerification
        }

        firebaseAuth.currentUser?.getIdToken(false)

        privateSettingsListener = firestore.collection("users").document(userId)
            .collection("private").document("settings")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                synchronized(listenerLock) {
                    if (listenerGen != activePrivateSettingsListenerGeneration.get() ||
                        firebaseAuth.currentUser?.uid != userId) {
                        return@addSnapshotListener
                    }

                    if (error != null) {
                        ArtifactLogger.e(
                            DiagnosticCategory.AUTH,
                            "PRIVATE_SETTINGS_CALLBACK_ERROR",
                            mapOf(
                                "path" to "users/$userId/private/settings",
                                "code" to error.code.name,
                                "message" to (error.message ?: ""),
                                "attempt" to attempt,
                                "timestamp" to System.currentTimeMillis()
                            ),
                            error
                        )

                        if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                            error.code == FirebaseFirestoreException.Code.UNAUTHENTICATED) {
                            if (attempt < MAX_LISTENER_ATTEMPTS) {
                                val calculatedBackoff = INITIAL_BACKOFF_MS * (1 shl (attempt - 1))
                                val backoffMs = calculatedBackoff.coerceAtMost(MAX_BACKOFF_MS)
                                ArtifactLogger.w(
                                    DiagnosticCategory.AUTH,
                                    "PRIVATE_SETTINGS_PERMISSION_DENIED_RETRYING",
                                    mapOf("path" to "users/$userId/private/settings", "attempt" to attempt, "backoffMs" to backoffMs)
                                )
                                privateSettingsRetryJob = repositoryScope.launch {
                                    delay(backoffMs)
                                    synchronized(listenerLock) {
                                        if (listenerGen == activePrivateSettingsListenerGeneration.get() &&
                                            firebaseAuth.currentUser?.uid == userId &&
                                            (_sessionState.value is SessionState.PendingServerVerification || _sessionState.value is SessionState.Active)) {
                                            observePrivateSettings(userId, attempt + 1)
                                        }
                                    }
                                }
                            } else {
                                _sessionState.value = SessionState.Unavailable(error)
                                ArtifactLogger.e(
                                    DiagnosticCategory.AUTH,
                                    "PRIVATE_SETTINGS_PERMISSION_DENIED_PERSISTENT",
                                    mapOf("path" to "users/$userId/private/settings", "attempt" to attempt)
                                )
                            }
                        } else {
                            _sessionState.value = SessionState.Unavailable(error)
                        }
                        return@addSnapshotListener
                    }

                    if (snapshot == null || snapshot.metadata.isFromCache) {
                        if (snapshot?.metadata?.isFromCache == true) {
                            ArtifactLogger.w(DiagnosticCategory.AUTH, "PRIVATE_SETTINGS_SNAPSHOT_FROM_CACHE_IGNORED")
                        }
                        return@addSnapshotListener
                    }

                    ArtifactLogger.i(
                        DiagnosticCategory.AUTH,
                        "PRIVATE_SETTINGS_CALLBACK_SUCCESS",
                        mapOf(
                            "path" to "users/$userId/private/settings",
                            "exists" to snapshot.exists(),
                            "timestamp" to System.currentTimeMillis()
                        )
                    )

                    val settings = if (snapshot.exists()) snapshot.toObject(UserPrivateSettings::class.java) else null
                    _privateSettings.value = settings

                    val serverActiveSessionId = settings?.activeSessionId
                    val activeDeviceName = settings?.activeDeviceName ?: "Unknown Device"

                    repositoryScope.launch {
                        val localSessionId = userSessionManager?.localSessionId?.first() ?: ""
                        synchronized(listenerLock) {
                            if (listenerGen != activePrivateSettingsListenerGeneration.get() ||
                                firebaseAuth.currentUser?.uid != userId) {
                                return@synchronized
                            }

                            when (_sessionState.value) {
                                is SessionState.PendingServerVerification -> {
                                    if (!snapshot.exists() || serverActiveSessionId.isNullOrEmpty()) {
                                        _sessionState.value = SessionState.NoActiveSession
                                    } else if (serverActiveSessionId == localSessionId) {
                                        _sessionState.value = SessionState.Active
                                    } else {
                                        _sessionState.value = SessionState.SessionExistsOnOtherDevice(activeDeviceName)
                                    }
                                }
                                is SessionState.Active -> {
                                    if (!snapshot.exists() || serverActiveSessionId.isNullOrEmpty()) {
                                        _sessionState.value = SessionState.NoActiveSession
                                    } else if (serverActiveSessionId != localSessionId) {
                                        ArtifactLogger.w(
                                            DiagnosticCategory.AUTH,
                                            "SESSION_REVOCATION_DETECTED",
                                            mapOf("serverSession" to (serverActiveSessionId ?: ""), "localSession" to localSessionId)
                                        )
                                        _sessionState.value = SessionState.Revoked
                                    }
                                }
                                else -> {
                                    // Maintain current terminal state
                                }
                            }
                        }
                    }
                }
            }
    }

    suspend fun awaitAuthoritativeSessionState(timeoutMs: Long = 10_000L): SessionState {
        val timeoutGen: Int
        val targetUserId: String?
        synchronized(listenerLock) {
            timeoutGen = activePrivateSettingsListenerGeneration.get()
            targetUserId = firebaseAuth.currentUser?.uid
        }

        val state = withTimeoutOrNull(timeoutMs) {
            _sessionState.first {
                it !is SessionState.Uninitialized && it !is SessionState.PendingServerVerification
            }
        }

        if (state != null) {
            return state
        }

        return synchronized(listenerLock) {
            if (timeoutGen == activePrivateSettingsListenerGeneration.get() &&
                firebaseAuth.currentUser?.uid == targetUserId &&
                _sessionState.value is SessionState.PendingServerVerification) {

                activePrivateSettingsListenerGeneration.incrementAndGet()
                privateSettingsListener?.remove()
                privateSettingsListener = null

                val timeoutState = SessionState.Unavailable(Exception("Authoritative session verification timed out"))
                _sessionState.value = timeoutState
                timeoutState
            } else {
                _sessionState.value
            }
        }
    }

    fun resetSessionVerification() {
        val uid = firebaseAuth.currentUser?.uid
        if (uid != null) {
            observePrivateSettings(uid)
        } else {
            synchronized(listenerLock) {
                privateSettingsListener?.remove()
                privateSettingsListener = null
                _sessionState.value = SessionState.Uninitialized
            }
        }
    }

    private fun cleanupListeners() {
        userDataRetryJob?.cancel()
        userDataRetryJob = null

        if (userDataListener != null) {
            val lifetime = System.currentTimeMillis() - userDataListenerCreatedAt
            ArtifactLogger.i(
                DiagnosticCategory.AUTH,
                "LISTENER_REMOVED",
                mapOf(
                    "path" to "userData",
                    "listenerId" to userDataListenerId,
                    "lifetimeMs" to lifetime,
                    "reason" to "CLEANUP"
                )
            )
            userDataListener?.remove()
            ArtifactLogger.i(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "userData"))
        }
        userDataListener = null
        
        privateSettingsRetryJob?.cancel()
        privateSettingsRetryJob = null

        if (privateSettingsListener != null) {
            privateSettingsListener?.remove()
            ArtifactLogger.i(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "privateSettings"))
        }
        privateSettingsListener = null
    }

    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser?> = authMutex.withLock {
        try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = firebaseAuth.signInWithCredential(credential).await()
            Result.success(result.user)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun reauthenticateWithGoogle(idToken: String): Result<Unit> = authMutex.withLock {
        val user = firebaseAuth.currentUser ?: return@withLock Result.failure(AppError.Unauthenticated())
        try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            user.reauthenticate(credential).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Refreshes the current user's session. For anonymous users, this can help
     * satisfy "recent login" requirements for destructive operations.
     */
    suspend fun refreshSession(): Result<Unit> {
        val user = firebaseAuth.currentUser ?: return Result.failure(AppError.Unauthenticated())
        return try {
            user.getIdToken(true).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun deleteCurrentUser(): Result<Unit> = authMutex.withLock {
        val user = firebaseAuth.currentUser ?: return@withLock Result.failure(AppError.Unauthenticated())
        try {
            // Hardening: Clear FCM token and Firestore reference before deletion while session is valid.
            // This ensures the device is de-registered even if deletion is interrupted.
            clearFcmToken()

            user.delete().await()
            Result.success(Unit)
        } catch (_: com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
            Result.failure(AppError.ReauthenticationRequired())
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun signOut(): Result<Unit> = authMutex.withLock {
        try {
            // Phase 2: Clear FCM token before signing out
            // Dependency: Requires active firebaseAuth.currentUser
            clearFcmToken()

            // Clear credential state (sign out from Google via Credential Manager)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            // Sign out from Firebase
            firebaseAuth.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Executes remote Firebase sign-out ONLY IF the currently active Firebase session
     * still belongs to the specified [targetUid] and session instance ID.
     * Serialized under [authMutex] to prevent sign-in/sign-out race conditions.
     */
    suspend fun signOutAuthorized(
        targetUid: String,
        sessionInstanceId: String,
        getLocalSessionId: suspend () -> String?
    ): Result<Unit> = authMutex.withLock {
        try {
            val currentUid = firebaseAuth.currentUser?.uid
            val currentSessionId = getLocalSessionId()

            val isUidMatch = currentUid != null && currentUid == targetUid
            val isSessionMatch = currentSessionId == null || currentSessionId == sessionInstanceId

            if (!isUidMatch || !isSessionMatch) {
                ArtifactLogger.i(
                    DiagnosticCategory.AUTH,
                    "SIGNOUT_AUTHORIZED_SKIPPED_SUPERSEDED",
                    mapOf("targetUid" to targetUid, "currentUid" to (currentUid ?: "null"))
                )
                return@withLock Result.success(Unit)
            }

            clearFcmToken()
            credentialManager.clearCredentialState(ClearCredentialStateRequest())

            val finalUid = firebaseAuth.currentUser?.uid
            val finalSessionId = getLocalSessionId()
            val finalUidMatch = finalUid != null && finalUid == targetUid
            val finalSessionMatch = finalSessionId == null || finalSessionId == sessionInstanceId

            if (!finalUidMatch || !finalSessionMatch) {
                ArtifactLogger.i(
                    DiagnosticCategory.AUTH,
                    "SIGNOUT_AUTHORIZED_SKIPPED_FINAL_CHECK",
                    mapOf("targetUid" to targetUid, "finalUid" to (finalUid ?: "null"))
                )
                return@withLock Result.success(Unit)
            }

            firebaseAuth.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Removes the FCM token from the user's private settings in Firestore and invalidates it locally.
     * This is called during sign-out to ensure the device no longer receives notifications for this user.
     *
     * IMPORTANT: This MUST execute before [firebaseAuth.signOut] because the user's UID
     * is required for the Firestore path.
     */
    internal suspend fun callCallable(functionName: String, data: Map<String, Any>): Map<*, *>? {
        val functions = firebaseFunctions ?: return null
        val callable = functions.getHttpsCallable(functionName)
        val result = callable.call(data as Any).await()
        return result.data as? Map<*, *>
    }

    suspend fun claimFirstDevice(deviceName: String, clientCorrelationId: String? = null): Result<ClaimResult> = authMutex.withLock {
        if (firebaseAuth.currentUser == null) return@withLock Result.failure(AppError.Unauthenticated())
        try {
            val data = hashMapOf<String, Any>(
                "deviceName" to deviceName,
                "clientCorrelationId" to (clientCorrelationId ?: UUID.randomUUID().toString())
            )
            val resultMap = callCallable("claimFirstDevice", data)
            val status = resultMap?.get("status") as? String
            val activeSessionId = resultMap?.get("activeSessionId") as? String
            val activeDeviceName = (resultMap?.get("activeDeviceName") as? String) ?: "Unknown Device"

            if (status == "CLAIM_SUCCESS" && !activeSessionId.isNullOrEmpty()) {
                userSessionManager?.setLocalSessionId(activeSessionId)
                val refreshResult = refreshSession()
                if (refreshResult.isSuccess) {
                    _sessionState.value = SessionState.Active
                    Result.success(ClaimResult.Success)
                } else {
                    val cause = refreshResult.exceptionOrNull() ?: Exception("Token refresh failed")
                    _sessionState.value = SessionState.TokenRefreshFailed(cause)
                    Result.failure(cause)
                }
            } else if (status == "SESSION_EXISTS") {
                _sessionState.value = SessionState.SessionExistsOnOtherDevice(activeDeviceName)
                Result.success(ClaimResult.SessionExists(activeDeviceName))
            } else {
                Result.failure(Exception("Unknown claim status: $status"))
            }
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.AUTH, "CLAIM_FIRST_DEVICE_FAILED", throwable = e)
            Result.failure(AppError.from(e))
        }
    }

    suspend fun transferActiveSession(deviceName: String, clientCorrelationId: String): Result<TransferResult> = authMutex.withLock {
        if (firebaseAuth.currentUser == null) return@withLock Result.failure(AppError.Unauthenticated())
        try {
            val data = hashMapOf<String, Any>(
                "deviceName" to deviceName,
                "clientCorrelationId" to clientCorrelationId
            )
            val resultMap = callCallable("transferActiveSession", data)
            val status = resultMap?.get("status") as? String
            val activeSessionId = resultMap?.get("activeSessionId") as? String

            if (status == "TRANSFER_SUCCESS" && !activeSessionId.isNullOrEmpty()) {
                userSessionManager?.setLocalSessionId(activeSessionId)
                val refreshResult = refreshSession()
                if (refreshResult.isSuccess) {
                    _sessionState.value = SessionState.Active
                    Result.success(TransferResult.Success)
                } else {
                    val cause = refreshResult.exceptionOrNull() ?: Exception("Token refresh failed")
                    _sessionState.value = SessionState.TokenRefreshFailed(cause)
                    Result.failure(cause)
                }
            } else {
                Result.failure(Exception("Transfer failed: status=$status"))
            }
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.AUTH, "TRANSFER_ACTIVE_SESSION_FAILED", throwable = e)
            Result.failure(AppError.from(e))
        }
    }

    private suspend fun clearFcmToken() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        try {
            // 1. Invalidate local FCM token to prevent reuse/leakage
            com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().await()
            
            // 2. Remove token reference from Firestore
            firestore.collection("users").document(uid)
                .collection("private").document("settings")
                .update("fcmToken", FieldValue.delete())
                .await()
        } catch (e: Exception) {
            // Handle failures gracefully as per requirement.
            // Failure to remove the token must NOT leave the application in an inconsistent logout state.
            // No identifiers (UID) are logged for privacy.
            ArtifactLogger.e(DiagnosticCategory.AUTH, "FCM_TOKEN_CLEAR_FAILED", throwable = e)
        }
    }

}
