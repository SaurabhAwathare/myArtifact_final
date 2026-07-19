package com.saurabh.artifact.repository

import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val credentialManager: CredentialManager,
    private val profileRepairService: com.saurabh.artifact.domain.auth.ProfileRepairService,
    private val diagnosticLogger: DiagnosticLogger
) {
    private val _currentUser = MutableStateFlow(firebaseAuth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    private val _userData = MutableStateFlow<User?>(null)
    val userData: StateFlow<User?> = _userData

    private val _privateSettings = MutableStateFlow<com.saurabh.artifact.model.UserPrivateSettings?>(null)
    val privateSettings: StateFlow<com.saurabh.artifact.model.UserPrivateSettings?> = _privateSettings

    private var userDataListener: ListenerRegistration? = null
    private var userDataListenerId: Int = -1
    private var userDataListenerCreatedAt: Long = 0
    private var privateSettingsListener: ListenerRegistration? = null

    private val listenerIdGenerator = java.util.concurrent.atomic.AtomicInteger(0)

    private var lastToken: String? = null

    val currentUserId: String
        get() = firebaseAuth.currentUser?.uid ?: ""

    init {
        firebaseAuth.addAuthStateListener { auth ->
            val user = auth.currentUser
            diagnosticLogger.info(
                DiagnosticCategory.AUTH, 
                "AUTH_STATE_CHANGED", 
                mapOf("uid" to (user?.uid ?: "null"), "timestamp" to System.currentTimeMillis())
            )
            _currentUser.value = user
            if (user != null) {
                observeUserData(user.uid)
                observePrivateSettings(user.uid)
            } else {
                cleanupListeners()
                _userData.value = null
                _privateSettings.value = null
            }
        }

        firebaseAuth.addIdTokenListener(object : FirebaseAuth.IdTokenListener {
            override fun onIdTokenChanged(auth: FirebaseAuth) {
                val user = auth.currentUser
                user?.getIdToken(false)?.addOnSuccessListener { result ->
                    val token = result.token
                    val tokenChanged = token != lastToken
                    lastToken = token

                    diagnosticLogger.info(
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
                    diagnosticLogger.info(DiagnosticCategory.AUTH, "ID_TOKEN_NULL", mapOf("timestamp" to System.currentTimeMillis()))
                }
            }
        })
    }

    private fun observeUserData(userId: String) {
        // Prevent duplicate listeners
        if (userDataListener != null) {
            val lifetime = System.currentTimeMillis() - userDataListenerCreatedAt
            diagnosticLogger.info(
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
            diagnosticLogger.info(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "users/$userId"))
        }

        val id = listenerIdGenerator.incrementAndGet()
        val createdAt = System.currentTimeMillis()
        userDataListenerId = id
        userDataListenerCreatedAt = createdAt

        diagnosticLogger.info(
            DiagnosticCategory.AUTH,
            "LISTENER_CREATED",
            mapOf(
                "listenerId" to id,
                "path" to "users/$userId",
                "createdAt" to createdAt,
                "timestamp" to System.currentTimeMillis()
            )
        )

        val tokenTask = firebaseAuth.currentUser?.getIdToken(false)
        val tokenReadyImmediate = tokenTask?.isSuccessful ?: false

        diagnosticLogger.info(
            DiagnosticCategory.AUTH,
            "RESEARCH_LISTENER_REGISTERED",
            mapOf(
                "listenerId" to id,
                "path" to "users/$userId",
                "uid" to (firebaseAuth.currentUser?.uid ?: "null"),
                "tokenReadyImmediate" to tokenReadyImmediate,
                "thread" to Thread.currentThread().name,
                "timestamp" to System.currentTimeMillis()
            )
        )

        userDataListener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    diagnosticLogger.error(
                        DiagnosticCategory.AUTH,
                        "SNAPSHOT_CALLBACK",
                        mapOf(
                            "listenerId" to id,
                            "path" to "users/$userId",
                            "type" to "ERROR",
                            "code" to error.code.name,
                            "message" to (error.message ?: ""),
                            "cause" to (error.cause?.toString() ?: "null"),
                            "timestamp" to System.currentTimeMillis()
                        ),
                        error
                    )
                    
                    if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        diagnosticLogger.debug(
                            DiagnosticCategory.AUTH,
                            "LISTENER_CALLBACK_END",
                            mapOf(
                                "listenerId" to id,
                                "reason" to "PERMISSION_DENIED",
                                "isRegistrationStillHeld" to (userDataListener != null),
                                "timestamp" to System.currentTimeMillis()
                            )
                        )
                        diagnosticLogger.debug(
                            DiagnosticCategory.AUTH,
                            "AUTH_REPOSITORY_STATE",
                            mapOf(
                                "listenerId" to id,
                                "uid" to (firebaseAuth.currentUser?.uid ?: "null"),
                                "userDataListenerHeld" to (userDataListener != null),
                                "userDataPopulated" to (_userData.value != null),
                                "timestamp" to System.currentTimeMillis()
                            )
                        )
                        diagnosticLogger.debug(
                            DiagnosticCategory.AUTH,
                            "USER_DATA_OBSERVE_PENDING",
                            mapOf("reason" to "Permission Denied (Profile not yet created)")
                        )
                    } else {
                        diagnosticLogger.error(
                            DiagnosticCategory.AUTH,
                            "USER_DATA_OBSERVE_FAILED",
                            mapOf(LogKeys.ERROR_CODE to error.code.name),
                            error
                        )
                    }
                    return@addSnapshotListener
                }

                diagnosticLogger.info(
                    DiagnosticCategory.AUTH,
                    "SNAPSHOT_CALLBACK",
                    mapOf(
                        "listenerId" to id,
                        "path" to "users/$userId",
                        "type" to "SUCCESS",
                        "exists" to (snapshot?.exists() ?: false),
                        "fromCache" to (snapshot?.metadata?.isFromCache ?: false),
                        "hasPendingWrites" to (snapshot?.metadata?.hasPendingWrites() ?: false),
                        "timestamp" to System.currentTimeMillis()
                    )
                )

                if (snapshot != null && snapshot.exists()) {
                    val (user, _) = profileRepairService.loadAndRepair(snapshot)
                    _userData.value = user
                } else {
                    _userData.value = null
                }
            }
    }

    private fun observePrivateSettings(userId: String) {
        if (privateSettingsListener != null) {
            privateSettingsListener?.remove()
            diagnosticLogger.info(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "users/$userId/private/settings"))
        }

        val tokenTask = firebaseAuth.currentUser?.getIdToken(false)
        val tokenReadyImmediate = tokenTask?.isSuccessful ?: false

        diagnosticLogger.info(
            DiagnosticCategory.AUTH,
            "FIRESTORE_LISTENER_REGISTERED",
            mapOf(
                "path" to "users/$userId/private/settings",
                "uid" to (firebaseAuth.currentUser?.uid ?: "null"),
                "tokenReadyImmediate" to tokenReadyImmediate,
                "thread" to Thread.currentThread().name,
                "timestamp" to System.currentTimeMillis()
            )
        )

        privateSettingsListener = firestore.collection("users").document(userId)
            .collection("private").document("settings")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    diagnosticLogger.error(
                        DiagnosticCategory.AUTH,
                        "SNAPSHOT_CALLBACK",
                        mapOf(
                            "path" to "users/$userId/private/settings",
                            "type" to "ERROR",
                            "code" to error.code.name,
                            "message" to (error.message ?: ""),
                            "cause" to (error.cause?.toString() ?: "null"),
                            "timestamp" to System.currentTimeMillis()
                        ),
                        error
                    )
                    
                    diagnosticLogger.error(
                        DiagnosticCategory.AUTH,
                        "PRIVATE_SETTINGS_OBSERVE_FAILED",
                        mapOf(LogKeys.ERROR_CODE to error.code.name),
                        error
                    )
                    return@addSnapshotListener
                }

                diagnosticLogger.info(
                    DiagnosticCategory.AUTH,
                    "SNAPSHOT_CALLBACK",
                    mapOf(
                        "path" to "users/$userId/private/settings",
                        "type" to "SUCCESS",
                        "exists" to (snapshot?.exists() ?: false),
                        "timestamp" to System.currentTimeMillis()
                    )
                )

                if (snapshot != null && snapshot.exists()) {
                    _privateSettings.value = snapshot.toObject(com.saurabh.artifact.model.UserPrivateSettings::class.java)
                } else {
                    _privateSettings.value = null
                }
            }
    }

    private fun cleanupListeners() {
        if (userDataListener != null) {
            val lifetime = System.currentTimeMillis() - userDataListenerCreatedAt
            diagnosticLogger.info(
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
            diagnosticLogger.info(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "userData"))
        }
        userDataListener = null
        
        if (privateSettingsListener != null) {
            privateSettingsListener?.remove()
            diagnosticLogger.info(DiagnosticCategory.AUTH, "LISTENER_TERMINATED", mapOf("path" to "privateSettings"))
        }
        privateSettingsListener = null
    }

    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser?> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = firebaseAuth.signInWithCredential(credential).await()
            Result.success(result.user)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun reauthenticateWithGoogle(idToken: String): Result<Unit> {
        val user = firebaseAuth.currentUser ?: return Result.failure(AppError.Unauthenticated())
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            user.reauthenticate(credential).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun deleteCurrentUser(): Result<Unit> {
        val user = firebaseAuth.currentUser ?: return Result.failure(AppError.Unauthenticated())
        return try {
            user.delete().await()
            Result.success(Unit)
        } catch (e: com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
            Result.failure(AppError.ReauthenticationRequired())
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
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
     * Removes the FCM token from the user's private settings in Firestore.
     * This is called during sign-out to ensure the device no longer receives notifications for this user.
     *
     * IMPORTANT: This MUST execute before [firebaseAuth.signOut] because the user's UID
     * is required for the Firestore path.
     */
    private suspend fun clearFcmToken() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        try {
            firestore.collection("users").document(uid)
                .collection("private").document("settings")
                .update("fcmToken", FieldValue.delete())
                .await()
        } catch (e: Exception) {
            // Handle failures gracefully as per requirement.
            // Failure to remove the token must NOT leave the application in an inconsistent logout state.
            // No identifiers (UID) are logged for privacy.
            diagnosticLogger.error(DiagnosticCategory.AUTH, "FCM_TOKEN_CLEAR_FAILED", throwable = e)
        }
    }

}
