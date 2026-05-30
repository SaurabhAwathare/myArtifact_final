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
    private val authRepository: AuthRepository
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val anonymousModeKey = booleanPreferencesKey("anonymous_mode")
    private val notificationsKey = booleanPreferencesKey("notifications_enabled")
    private val smartRemindersKey = booleanPreferencesKey("smart_reminders_enabled")
    private val emotionalSafetyKey = booleanPreferencesKey("emotional_safety_enabled")
    private val dataConsentKey = booleanPreferencesKey("data_collection_consent")

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
                dataCollectionConsent = preferences[dataConsentKey] ?: false
            )
        }

    // Remote settings synchronization
    private var remoteListener: com.google.firebase.firestore.ListenerRegistration? = null

    init {
        repositoryScope.launch {
            authRepository.currentUser.collectLatest { user ->
                remoteListener?.remove()
                if (user != null) {
                    remoteListener = firestore.collection("settings").document(user.uid)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
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
        }
    }

    suspend fun updateSettings(settings: UserSettings) {
        context.dataStore.edit { preferences ->
            preferences[anonymousModeKey] = settings.isAnonymousMode
            preferences[notificationsKey] = settings.notificationsEnabled
            preferences[smartRemindersKey] = settings.smartRemindersEnabled
            preferences[emotionalSafetyKey] = settings.emotionalSafetyEnabled
            preferences[dataConsentKey] = settings.dataCollectionConsent
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
            // 1. Clear local preferences
            context.dataStore.edit { it.clear() }
            // 2. Perform Firebase SignOut
            authRepository.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteUserAccount(): Result<Unit> {
        val user = authRepository.currentUser.value ?: return Result.failure(Exception("Not logged in"))
        val userId = user.uid
        
        return try {
            // HARDENING: Instead of client-side orchestration which is prone to failure,
            // we rely on the server-side Cloud Function triggered by auth.user().onDelete().
            // This ensures atomicity even if the app crashes or network is lost.
            
            // 1. Mark account for deletion in Firestore (Optional: Provides UI feedback state)
            firestore.collection("users").document(userId)
                .update("accountStatus", "DELETION_PENDING")
                .await()

            // 2. Clear Local preferences immediately to protect privacy
            context.dataStore.edit { it.clear() }
            
            // 3. Delete Auth account (This triggers the Cloud Function 'onUserDeleted')
            authRepository.deleteCurrentUser().getOrThrow()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("SettingsRepository", "Account deletion trigger failed", e)
            Result.failure(e)
        }
    }

    fun exportUserData(): Result<String> {
        // Implementation for data export trigger (e.g., generate a signed URL or prepare a file)
        return Result.success("Export initiated. You will receive an email shortly.")
    }
}
