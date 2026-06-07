package com.saurabh.artifact.repository

import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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
) {
    private val _currentUser = MutableStateFlow(firebaseAuth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    private val _userData = MutableStateFlow<User?>(null)
    val userData: StateFlow<User?> = _userData

    private val _privateSettings = MutableStateFlow<com.saurabh.artifact.model.UserPrivateSettings?>(null)
    val privateSettings: StateFlow<com.saurabh.artifact.model.UserPrivateSettings?> = _privateSettings

    private var userDataListener: ListenerRegistration? = null
    private var privateSettingsListener: ListenerRegistration? = null

    val currentUserId: String
        get() = firebaseAuth.currentUser?.uid ?: ""

    init {
        firebaseAuth.addAuthStateListener { auth ->
            val user = auth.currentUser
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
    }

    private fun observeUserData(userId: String) {
        // Prevent duplicate listeners
        userDataListener?.remove()

        userDataListener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        android.util.Log.d("AuthRepository", "Waiting for profile creation... (Permission Denied)")
                    } else {
                        android.util.Log.e("AuthRepository", "Error observing user data: ${error.message}")
                    }
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    snapshot.toObject(User::class.java)?.let {
                        _userData.value = it.copy(id = snapshot.id)
                    }
                } else {
                    _userData.value = null
                }
            }
    }

    private fun observePrivateSettings(userId: String) {
        privateSettingsListener?.remove()

        privateSettingsListener = firestore.collection("users").document(userId)
            .collection("private").document("settings")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("AuthRepository", "Error observing private settings: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    _privateSettings.value = snapshot.toObject(com.saurabh.artifact.model.UserPrivateSettings::class.java)
                } else {
                    _privateSettings.value = null
                }
            }
    }

    private fun cleanupListeners() {
        userDataListener?.remove()
        userDataListener = null
        privateSettingsListener?.remove()
        privateSettingsListener = null
    }

    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser?> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = firebaseAuth.signInWithCredential(credential).await()
            Result.success(result.user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reauthenticateWithGoogle(idToken: String): Result<Unit> {
        val user = firebaseAuth.currentUser ?: return Result.failure(Exception("No user logged in"))
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            user.reauthenticate(credential).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCurrentUser(): Result<Unit> {
        val user = firebaseAuth.currentUser ?: return Result.failure(Exception("No user logged in"))
        return try {
            user.delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
            // Clear credential state (sign out from Google via Credential Manager)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            // Sign out from Firebase
            firebaseAuth.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
