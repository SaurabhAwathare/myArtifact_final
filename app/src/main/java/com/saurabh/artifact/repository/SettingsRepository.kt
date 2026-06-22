package com.saurabh.artifact.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.emptyPreferences
import com.google.firebase.firestore.FirebaseFirestore
import android.util.Log
import com.saurabh.artifact.model.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val authRepository: AuthRepository,
    private val sessionManager: com.saurabh.artifact.data.local.UserSessionManager,
    private val logoutCoordinator: dagger.Lazy<com.saurabh.artifact.domain.auth.LogoutCoordinator>
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val anonymousModeKey = booleanPreferencesKey("anonymous_mode")
    private val notificationsKey = booleanPreferencesKey("notifications_enabled")
    private val smartRemindersKey = booleanPreferencesKey("smart_reminders_enabled")
    private val emotionalSafetyKey = booleanPreferencesKey("emotional_safety_enabled")
    private val dataConsentKey = booleanPreferencesKey("data_collection_consent")
    private val biometricLockKey = booleanPreferencesKey("biometric_lock_enabled")
    private val autoLockKey = booleanPreferencesKey("auto_lock_enabled")
    private val stealthModeKey = booleanPreferencesKey("stealth_mode_enabled")

    val userSettings: Flow<UserSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            UserSettings(
                isAnonymousMode = preferences[anonymousModeKey] ?: true,
                notificationsEnabled = preferences[notificationsKey] ?: true,
                smartRemindersEnabled = preferences[smartRemindersKey] ?: true,
                emotionalSafetyEnabled = preferences[emotionalSafetyKey] ?: true,
                dataCollectionConsent = preferences[dataConsentKey] ?: false,
                biometricLockEnabled = preferences[biometricLockKey] ?: false,
                autoLockEnabled = preferences[autoLockKey] ?: true,
                stealthModeEnabled = preferences[stealthModeKey] ?: false
            )
        }

    // Remote settings synchronization
    private var remoteListener: com.google.firebase.firestore.ListenerRegistration? = null

    init {
        repositoryScope.launch {
            authRepository.currentUser.collectLatest { user ->
                remoteListener?.remove()
                if (user != null) {
                    // HARDENING: Wait for profile to be fully ready before subscribing
                    // This prevents PERMISSION_DENIED spam during registration/recovery
                    var retryCount = 0
                    while (retryCount < 5) {
                        try {
                            val snapshot = firestore.collection("users").document(user.uid).get().await()
                            if (snapshot.exists()) break
                        } catch (e: Exception) {
                            Log.d("SettingsRepository", "Profile not ready yet, retrying... (${e.message})")
                        }
                        kotlinx.coroutines.delay(1000)
                        retryCount++
                    }

                    remoteListener = firestore.collection("settings").document(user.uid)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                    Log.d("SettingsRepository", "Settings access denied. Profile might still be initializing.")
                                } else {
                                    Log.e("SettingsRepository", "Error observing settings: ${error.message}")
                                }
                                return@addSnapshotListener
                            }
                            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val remoteSettings = snapshot.toObject(UserSettings::class.java) ?: return@addSnapshotListener
                            repositoryScope.launch {
                                updateLocalSettings(remoteSettings)
                            }
                        }
                }
            }
        }
    }

    private suspend fun updateLocalSettings(settings: UserSettings) {
        context.dataStore.edit { preferences ->
            preferences[anonymousModeKey] = settings.isAnonymousMode
            preferences[notificationsKey] = settings.notificationsEnabled
            preferences[smartRemindersKey] = settings.smartRemindersEnabled
            preferences[emotionalSafetyKey] = settings.emotionalSafetyEnabled
            preferences[dataConsentKey] = settings.dataCollectionConsent
            preferences[biometricLockKey] = settings.biometricLockEnabled
            preferences[autoLockKey] = settings.autoLockEnabled
            preferences[stealthModeKey] = settings.stealthModeEnabled
        }
    }

    suspend fun updateSettings(settings: UserSettings) {
        context.dataStore.edit { preferences ->
            preferences[anonymousModeKey] = settings.isAnonymousMode
            preferences[notificationsKey] = settings.notificationsEnabled
            preferences[smartRemindersKey] = settings.smartRemindersEnabled
            preferences[emotionalSafetyKey] = settings.emotionalSafetyEnabled
            preferences[dataConsentKey] = settings.dataCollectionConsent
            preferences[biometricLockKey] = settings.biometricLockEnabled
            preferences[autoLockKey] = settings.autoLockEnabled
            preferences[stealthModeKey] = settings.stealthModeEnabled
        }
        syncToRemote(settings)
    }

    private suspend fun syncToRemote(settings: UserSettings) {
        val userId = authRepository.currentUser.value?.uid ?: return
        try {
            firestore.collection("settings").document(userId)
                .set(settings.copy(lastSyncTimestamp = System.currentTimeMillis()))
                .await()
        } catch (_: Exception) {
            // Handle failure (e.g., log or retry later)
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
            // Clear local preferences (Settings)
            context.dataStore.edit { it.clear() }
            
            // Note: Broader session cleanup and Auth signOut is now handled by LogoutCoordinator.
            // We only clear our own DataStore here for separation of concerns.
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteUserAccount(): Result<Unit> {
        val user = authRepository.currentUser.value ?: return Result.failure(Exception("Not logged in"))
        val userId = user.uid
        
        return try {
            // 1. Mark account for deletion in Firestore (Optional: Provides UI feedback state)
            firestore.collection("users").document(userId)
                .update("accountStatus", "DELETION_PENDING")
                .await()

            // 2. Perform Full Local Cleanup (Hardening)
            logoutCoordinator.get().performFullCleanup()
            
            // 3. Delete Auth account (This triggers the Cloud Function 'onUserDeleted')
            authRepository.deleteCurrentUser().getOrThrow()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("SettingsRepository", "Account deletion trigger failed", e)
            Result.failure(e)
        }
    }
}
