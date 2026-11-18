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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
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
import com.google.firebase.messaging.FirebaseMessaging
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.model.UserPrivateSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

sealed class RemoteProfileState {
    abstract val uid: String

    data class Loading(override val uid: String) : RemoteProfileState()
    data class CachedAvailable(override val uid: String, val user: User) : RemoteProfileState()
    data class Exists(override val uid: String, val user: User) : RemoteProfileState()
    data class Unreadable(override val uid: String, val cause: Throwable? = null) : RemoteProfileState()
    data class NotFound(override val uid: String) : RemoteProfileState()
    data class Unavailable(override val uid: String, val cause: Throwable? = null) : RemoteProfileState()
}

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

sealed class SessionGateResult {
    object Approved : SessionGateResult()
    data class Conflict(val activeDeviceName: String) : SessionGateResult()
    data class Failure(val error: Throwable) : SessionGateResult()
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

    suspend fun awaitAuthRestoration(timeoutMs: Long = 5_000L): Boolean {
        if (_isRestored.value) return true
        return withTimeoutOrNull(timeoutMs) {
            _isRestored.first { it }
        } ?: false
    }

    private val _userData = MutableStateFlow<User?>(null)
    val userData: StateFlow<User?> = _userData

    private val _remoteProfileState = MutableStateFlow<RemoteProfileState?>(null)
    val remoteProfileState: StateFlow<RemoteProfileState?> = _remoteProfileState.asStateFlow()

    private val _privateSettings = MutableStateFlow<com.saurabh.artifact.model.UserPrivateSettings?>(null)
    val privateSettings: StateFlow<com.saurabh.artifact.model.UserPrivateSettings?> = _privateSettings

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Uninitialized)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    companion object {
        internal const val MAX_LISTENER_ATTEMPTS = 5
        internal const val INITIAL_BACKOFF_MS = 200L
        internal const val MAX_BACKOFF_MS = 1000L
    }

    internal var userDataListener: ListenerRegistration? = null
    internal var userDataListenerId: Int = -1
    private var userDataListenerCreatedAt: Long = 0
    internal var userDataRetryJob: Job? = null
    internal val activeUserDataListenerGeneration = AtomicInteger(0)

    internal var privateSettingsListener: ListenerRegistration? = null
    internal var privateSettingsRetryJob: Job? = null
    internal val activePrivateSettingsListenerGeneration = AtomicInteger(0)
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
            val previousUser = _currentUser.value
            _currentUser.value = user
            _isRestored.value = true
            if (user != null) {
                synchronized(listenerLock) {
                    if (previousUser != null && previousUser.uid != user.uid) {
                        _userData.value = null
                        _remoteProfileState.value = RemoteProfileState.Loading(user.uid)
                        cleanupListeners()
                    } else if (_userData.value != null && _userData.value?.id != user.uid) {
                        _userData.value = null
                        _remoteProfileState.value = RemoteProfileState.Loading(user.uid)
                    }
                }
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

    internal fun observeUserData(userId: String, attempt: Int = 1) {
        val listenerGen: Int
        val id = listenerIdGenerator.incrementAndGet()
        val createdAt = System.currentTimeMillis()

        synchronized(listenerLock) {
            userDataRetryJob?.cancel()
            userDataRetryJob = null

            if (firebaseAuth.currentUser?.uid != userId) {
                ArtifactLogger.w(DiagnosticCategory.AUTH, "USER_DATA_LISTEN_ABORTED_USER_MISMATCH")
                return
            }

            // Immediately invalidate Account A's in-memory userData if switching to Account B
            if (_userData.value != null && _userData.value?.id != userId) {
                _userData.value = null
            }

            if (attempt == 1) {
                _remoteProfileState.value = RemoteProfileState.Loading(userId)
                if (_userData.value?.id != userId) {
                    _userData.value = null
                }
            }

            listenerGen = activeUserDataListenerGeneration.incrementAndGet()
        }

        ArtifactLogger.i(
            DiagnosticCategory.AUTH,
            "LISTENER_CREATED",
            mapOf(
                "listenerId" to id,
                "path" to "users/$userId",
                "attempt" to attempt,
                "generation" to listenerGen,
                "createdAt" to createdAt,
                "timestamp" to System.currentTimeMillis()
            )
        )

        firebaseAuth.currentUser?.getIdToken(false)

        val registration = firestore.collection("users").document(userId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                synchronized(listenerLock) {
                    if (listenerGen != activeUserDataListenerGeneration.get() ||
                        firebaseAuth.currentUser?.uid != userId) {
                        ArtifactLogger.w(
                            DiagnosticCategory.AUTH,
                            "USER_DATA_CALLBACK_STALE_IGNORED",
                            mapOf(
                                "capturedGen" to listenerGen,
                                "activeGen" to activeUserDataListenerGeneration.get(),
                                "capturedUid" to userId,
                                "currentUid" to (firebaseAuth.currentUser?.uid ?: "null")
                            )
                        )
                        return@addSnapshotListener
                    }

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

                        userDataListener?.remove()
                        userDataListener = null

                        _remoteProfileState.value = RemoteProfileState.Unavailable(userId, error)
                        // Preserve same-account in-memory profile during transient errors
                        if (firebaseAuth.currentUser?.uid != userId || _userData.value?.id != userId) {
                            _userData.value = null
                        }

                        val isRetryable = when (error.code) {
                            FirebaseFirestoreException.Code.PERMISSION_DENIED,
                            FirebaseFirestoreException.Code.UNAUTHENTICATED,
                            FirebaseFirestoreException.Code.UNAVAILABLE,
                            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> true
                            else -> false
                        }

                        if (isRetryable && attempt < MAX_LISTENER_ATTEMPTS) {
                            val calculatedBackoff = INITIAL_BACKOFF_MS * (1 shl (attempt - 1))
                            val backoffMs = calculatedBackoff.coerceAtMost(MAX_BACKOFF_MS)
                            ArtifactLogger.w(
                                DiagnosticCategory.AUTH,
                                "USER_DATA_RETRYABLE_ERROR_SCHEDULING_RETRY",
                                mapOf("path" to "users/$userId", "code" to error.code.name, "attempt" to attempt, "backoffMs" to backoffMs)
                            )
                            userDataRetryJob?.cancel()
                            userDataRetryJob = repositoryScope.launch {
                                delay(backoffMs)
                                val shouldRetry = synchronized(listenerLock) {
                                    listenerGen == activeUserDataListenerGeneration.get() &&
                                        firebaseAuth.currentUser?.uid == userId
                                }
                                if (shouldRetry) {
                                    observeUserData(userId, attempt + 1)
                                } else {
                                    ArtifactLogger.w(
                                        DiagnosticCategory.AUTH,
                                        "USER_DATA_RETRY_STALE_IGNORED",
                                        mapOf(
                                            "capturedGen" to listenerGen,
                                            "activeGen" to activeUserDataListenerGeneration.get(),
                                            "capturedUid" to userId,
                                            "currentUid" to (firebaseAuth.currentUser?.uid ?: "null")
                                        )
                                    )
                                }
                            }
                        } else {
                            ArtifactLogger.e(
                                DiagnosticCategory.AUTH,
                                if (isRetryable) "USER_DATA_RETRY_EXHAUSTED" else "USER_DATA_NON_RETRYABLE_ERROR",
                                mapOf("path" to "users/$userId", "code" to error.code.name, "attempt" to attempt)
                            )
                        }
                        return@addSnapshotListener
                    }

                    if (snapshot == null) {
                        _remoteProfileState.value = RemoteProfileState.Unavailable(userId, Exception("Snapshot is null"))
                        if (firebaseAuth.currentUser?.uid != userId || _userData.value?.id != userId) {
                            _userData.value = null
                        }
                        return@addSnapshotListener
                    }

                    val isFromCache = snapshot.metadata.isFromCache

                    ArtifactLogger.i(
                        DiagnosticCategory.AUTH,
                        "SNAPSHOT_CALLBACK_SUCCESS",
                        mapOf(
                            "listenerId" to id,
                            "path" to "users/$userId",
                            "exists" to snapshot.exists(),
                            "fromCache" to isFromCache,
                            "hasPendingWrites" to snapshot.metadata.hasPendingWrites(),
                            "timestamp" to System.currentTimeMillis()
                        )
                    )

                    if (!snapshot.exists()) {
                        if (!isFromCache) {
                            _remoteProfileState.value = RemoteProfileState.NotFound(userId)
                            _userData.value = null
                        } else {
                            ArtifactLogger.d(DiagnosticCategory.AUTH, "USER_DATA_CACHE_MISS_WAITING_SERVER")
                        }
                        return@addSnapshotListener
                    }

                    val deserializedUser: User? = try {
                        snapshot.toObject(User::class.java)?.copy(id = snapshot.id)
                    } catch (e: Exception) {
                        ArtifactLogger.e(DiagnosticCategory.AUTH, "USER_DATA_DESERIALIZATION_FAILED", mapOf("uid" to userId), e)
                        null
                    }

                    if (deserializedUser == null) {
                        val cause = Exception("Failed to deserialize User model or document data is empty")
                        _remoteProfileState.value = RemoteProfileState.Unreadable(userId, cause)
                        _userData.value = null
                        return@addSnapshotListener
                    }

                    // Missing or blank required persona anchor (anonymousId) -> Unreadable
                    if (deserializedUser.anonymousId.isBlank()) {
                        val cause = Exception("Malformed User model: missing required persona anchor (anonymousId)")
                        _remoteProfileState.value = RemoteProfileState.Unreadable(userId, cause)
                        _userData.value = null
                        return@addSnapshotListener
                    }

                    // Valid document with blank anonymousName or incomplete Sigil config remains Exists/CachedAvailable
                    if (firebaseAuth.currentUser?.uid == userId && deserializedUser.id == userId) {
                        _userData.value = deserializedUser
                        if (isFromCache) {
                            _remoteProfileState.value = RemoteProfileState.CachedAvailable(userId, deserializedUser)
                        } else {
                            _remoteProfileState.value = RemoteProfileState.Exists(userId, deserializedUser)
                        }
                    }
                }
            }

        synchronized(listenerLock) {
            if (listenerGen == activeUserDataListenerGeneration.get() &&
                firebaseAuth.currentUser?.uid == userId) {

                userDataListener?.let { oldListener ->
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
                    oldListener.remove()
                    ArtifactLogger.i(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "users/$userId"))
                }

                userDataListener = registration
                userDataListenerId = id
                userDataListenerCreatedAt = createdAt

                ArtifactLogger.i(
                    DiagnosticCategory.AUTH,
                    "LISTENER_INSTALLED",
                    mapOf(
                        "listenerId" to id,
                        "path" to "users/$userId",
                        "attempt" to attempt,
                        "generation" to listenerGen,
                        "createdAt" to createdAt,
                        "timestamp" to System.currentTimeMillis()
                    )
                )
            } else {
                ArtifactLogger.w(
                    DiagnosticCategory.AUTH,
                    "LISTENER_REGISTRATION_SUPERSEDED_REMOVED",
                    mapOf(
                        "capturedGen" to listenerGen,
                        "activeGen" to activeUserDataListenerGeneration.get(),
                        "capturedUid" to userId,
                        "currentUid" to (firebaseAuth.currentUser?.uid ?: "null")
                    )
                )
                registration.remove()
            }
        }
    }

    internal fun observePrivateSettings(userId: String, attempt: Int = 1) {
        val listenerGen: Int
        synchronized(listenerLock) {
            privateSettingsRetryJob?.cancel()
            privateSettingsRetryJob = null

            if (firebaseAuth.currentUser?.uid != userId) {
                ArtifactLogger.w(DiagnosticCategory.AUTH, "PRIVATE_SETTINGS_LISTEN_ABORTED_USER_MISMATCH")
                return
            }

            listenerGen = activePrivateSettingsListenerGeneration.incrementAndGet()
            _privateSettings.value = null
            _sessionState.value = SessionState.PendingServerVerification
        }

        firebaseAuth.currentUser?.getIdToken(false)

        val registration = firestore.collection("users").document(userId)
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

                        privateSettingsListener?.remove()
                        privateSettingsListener = null

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
                                privateSettingsRetryJob?.cancel()
                                privateSettingsRetryJob = repositoryScope.launch {
                                    delay(backoffMs)
                                    val shouldRetry = synchronized(listenerLock) {
                                        listenerGen == activePrivateSettingsListenerGeneration.get() &&
                                            firebaseAuth.currentUser?.uid == userId &&
                                            (_sessionState.value is SessionState.PendingServerVerification || _sessionState.value is SessionState.Active)
                                    }
                                    if (shouldRetry) {
                                        observePrivateSettings(userId, attempt + 1)
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

        synchronized(listenerLock) {
            if (listenerGen == activePrivateSettingsListenerGeneration.get() &&
                firebaseAuth.currentUser?.uid == userId) {

                privateSettingsListener?.let { oldListener ->
                    oldListener.remove()
                    ArtifactLogger.i(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "users/$userId/private/settings"))
                }
                privateSettingsListener = registration
            } else {
                ArtifactLogger.w(
                    DiagnosticCategory.AUTH,
                    "PRIVATE_SETTINGS_LISTENER_SUPERSEDED_REMOVED",
                    mapOf(
                        "capturedGen" to listenerGen,
                        "activeGen" to activePrivateSettingsListenerGeneration.get(),
                        "capturedUid" to userId,
                        "currentUid" to (firebaseAuth.currentUser?.uid ?: "null")
                    )
                )
                registration.remove()
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

    suspend fun resolveSessionGate(deviceName: String): SessionGateResult {
        val user = firebaseAuth.currentUser ?: return SessionGateResult.Failure(AppError.Unauthenticated())

        return try {
            val session = awaitAuthoritativeSessionState()
            when (session) {
                is SessionState.Active -> {
                    val localSessionId = userSessionManager?.localSessionId?.first() ?: ""
                    var tokenResult = user.getIdToken(false).await()
                    var tokenClaim = tokenResult.claims["activeSessionId"] as? String

                    if (tokenClaim != localSessionId || tokenClaim.isNullOrEmpty()) {
                        val refreshResult = refreshSession()
                        if (refreshResult.isFailure) {
                            return SessionGateResult.Failure(
                                refreshResult.exceptionOrNull() ?: Exception("Token refresh failed")
                            )
                        }
                        tokenResult = user.getIdToken(false).await()
                        tokenClaim = tokenResult.claims["activeSessionId"] as? String
                    }

                    if (tokenClaim == localSessionId && localSessionId.isNotEmpty()) {
                        SessionGateResult.Approved
                    } else {
                        SessionGateResult.Failure(Exception("Token claim activeSessionId mismatch"))
                    }
                }
                is SessionState.NoActiveSession -> {
                    val claimRes = claimFirstDevice(deviceName)
                    if (claimRes.isSuccess) {
                        when (val claimVal = claimRes.getOrNull()) {
                            is ClaimResult.Success -> {
                                val localSessionId = userSessionManager?.localSessionId?.first() ?: ""
                                val tokenClaim = user.getIdToken(false).await().claims["activeSessionId"] as? String
                                if (tokenClaim == localSessionId && localSessionId.isNotEmpty()) {
                                    SessionGateResult.Approved
                                } else {
                                    SessionGateResult.Failure(Exception("Token claim activeSessionId mismatch after claim"))
                                }
                            }
                            is ClaimResult.SessionExists -> {
                                SessionGateResult.Conflict(claimVal.activeDeviceName)
                            }
                            is ClaimResult.Failure -> {
                                SessionGateResult.Failure(claimVal.error)
                            }
                            null -> SessionGateResult.Failure(Exception("Unknown session claim result"))
                        }
                    } else {
                        val err = claimRes.exceptionOrNull() ?: Exception("Failed to claim active session")
                        SessionGateResult.Failure(err)
                    }
                }
                is SessionState.SessionExistsOnOtherDevice -> {
                    SessionGateResult.Conflict(session.deviceName)
                }
                is SessionState.Revoked -> {
                    SessionGateResult.Failure(AppError.Unauthenticated())
                }
                is SessionState.TokenRefreshFailed -> {
                    SessionGateResult.Failure(session.cause)
                }
                is SessionState.Unavailable -> {
                    SessionGateResult.Failure(session.cause)
                }
                is SessionState.Uninitialized,
                is SessionState.PendingServerVerification -> {
                    SessionGateResult.Failure(Exception("Unresolved session state"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.AUTH, "RESOLVE_SESSION_GATE_FAILED", throwable = e)
            SessionGateResult.Failure(AppError.from(e))
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

    internal fun cleanupListeners() {
        synchronized(listenerLock) {
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
            activeUserDataListenerGeneration.incrementAndGet()
            _remoteProfileState.value = null
            _userData.value = null

            privateSettingsRetryJob?.cancel()
            privateSettingsRetryJob = null

            if (privateSettingsListener != null) {
                privateSettingsListener?.remove()
                ArtifactLogger.i(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "privateSettings"))
            }
            privateSettingsListener = null
            activePrivateSettingsListenerGeneration.incrementAndGet()
            _privateSettings.value = null
        }
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ArtifactLogger.e(DiagnosticCategory.AUTH, "TRANSFER_ACTIVE_SESSION_FAILED", throwable = e)
            Result.failure(AppError.from(e))
        }
    }

    private suspend fun clearFcmToken() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        try {
            withTimeoutOrNull(2000L) {
                coroutineScope {
                    val fcmJob = launch {
                        runCatching {
                            FirebaseMessaging.getInstance().deleteToken().await()
                        }.onFailure { e ->
                            if (e !is CancellationException) {
                                ArtifactLogger.e(DiagnosticCategory.AUTH, "FCM_TOKEN_DELETE_FAILED", throwable = e)
                            }
                        }
                    }

                    val firestoreJob = launch {
                        runCatching {
                            firestore.collection("users").document(uid)
                                .collection("private").document("settings")
                                .update("fcmToken", FieldValue.delete())
                                .await()
                        }.onFailure { e ->
                            if (e !is CancellationException) {
                                ArtifactLogger.e(DiagnosticCategory.AUTH, "FIRESTORE_FCM_TOKEN_CLEAR_FAILED", throwable = e)
                            }
                        }
                    }

                    joinAll(fcmJob, firestoreJob)
                }
            } ?: run {
                ArtifactLogger.w(DiagnosticCategory.AUTH, "FCM_TOKEN_CLEAR_TIMEOUT")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ArtifactLogger.e(DiagnosticCategory.AUTH, "FCM_TOKEN_CLEAR_FAILED", throwable = e)
        }
    }

}
