package com.saurabh.artifact.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.saurabh.artifact.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class UserSessionManager @Inject constructor(
    @Named("sessionDataStore") private val dataStore: DataStore<Preferences>,
    private val blockStoreManager: BlockStoreManager,
) {
    private object PreferencesKeys {
        val ANONYMOUS_ID = stringPreferencesKey("anonymous_id")
        val SIGIL_SEED = stringPreferencesKey("sigil_seed")
        val SIGIL_CONFIG_JSON = stringPreferencesKey("sigil_config_json")
        val SIGIL_COLOR = stringPreferencesKey("sigil_color")
        val USERNAME = stringPreferencesKey("username")
        val SIGIL = stringPreferencesKey("sigil")
        val IS_ANONYMOUS = booleanPreferencesKey("is_anonymous")
        val RESONANCE_IN = longPreferencesKey("resonance_in")
        val RESONANCE_OUT = longPreferencesKey("resonance_out")
        val ACTIVE_DRAFT_ID = stringPreferencesKey("active_draft_id")
        val ACTIVE_PROMPT_ID = stringPreferencesKey("active_prompt_id")
    }

    /**
     * Retrieves the current anonymous profile. 
     */
    val userProfile: Flow<UserProfile> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val id = preferences[PreferencesKeys.ANONYMOUS_ID] ?: ("gen_" + UUID.randomUUID().toString().take(8))
            
            val seed = preferences[PreferencesKeys.SIGIL_SEED] ?: UUID.randomUUID().toString()
                
            val username = preferences[PreferencesKeys.USERNAME] ?: com.saurabh.artifact.util.UsernameGenerator.generate()
            val sigil = preferences[PreferencesKeys.SIGIL] ?: com.saurabh.artifact.util.UsernameGenerator.deriveSigil(id)
            val sigilColor = preferences[PreferencesKeys.SIGIL_COLOR] ?: "#FFD700"
            val isAnonymous = preferences[PreferencesKeys.IS_ANONYMOUS] ?: true
            val resonanceIn = preferences[PreferencesKeys.RESONANCE_IN] ?: 0L
            val resonanceOut = preferences[PreferencesKeys.RESONANCE_OUT] ?: 0L
            
            val configJson = preferences[PreferencesKeys.SIGIL_CONFIG_JSON]

            val config = configJson?.let { 
                try {
                    Json.decodeFromString<com.saurabh.artifact.model.SigilConfig>(it).copy(seed = seed)
                } catch (_: Exception) {
                    com.saurabh.artifact.model.SigilConfig(seed = seed)
                }
            } ?: com.saurabh.artifact.model.SigilConfig(seed = seed)
            
            UserProfile(
                anonymousId = id, 
                username = username, 
                sigil = sigil,
                sigilSeed = seed,
                sigilColor = sigilColor,
                sigilConfig = config,
                isAnonymous = isAnonymous,
                resonanceInCount = resonanceIn,
                resonanceOutCount = resonanceOut
            )
        }

    val activePromptId: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.ACTIVE_PROMPT_ID] }

    suspend fun setActiveDraftId(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) {
                preferences.remove(PreferencesKeys.ACTIVE_DRAFT_ID)
            } else {
                preferences[PreferencesKeys.ACTIVE_DRAFT_ID] = id
            }
        }
    }

    suspend fun setActivePromptId(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) {
                preferences.remove(PreferencesKeys.ACTIVE_PROMPT_ID)
            } else {
                preferences[PreferencesKeys.ACTIVE_PROMPT_ID] = id
            }
        }
    }

    /**
     * Updates the user's sigil configuration.
     */
    suspend fun updateSigilConfig(config: com.saurabh.artifact.model.SigilConfig) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SIGIL_CONFIG_JSON] = Json.encodeToString(config)
            preferences[PreferencesKeys.SIGIL_SEED] = config.seed
        }
    }

    /**
     * Updates the user's identity username.
     */
    suspend fun updateUsername(username: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.USERNAME] = username
        }
    }

    /**
     * Synchronizes local DataStore with a remote User profile from Firestore.
     */
    suspend fun syncFromRemote(user: com.saurabh.artifact.model.User) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ANONYMOUS_ID] = user.anonymousId
            preferences[PreferencesKeys.USERNAME] = user.anonymousName
            preferences[PreferencesKeys.SIGIL] = user.anonymousSigil
            preferences[PreferencesKeys.SIGIL_SEED] = user.sigilSeed
            preferences[PreferencesKeys.SIGIL_COLOR] = user.sigilColor
            preferences[PreferencesKeys.SIGIL_CONFIG_JSON] = Json.encodeToString(user.sigilConfig)
            preferences[PreferencesKeys.IS_ANONYMOUS] = user.isAnonymous
            preferences[PreferencesKeys.RESONANCE_IN] = user.resonanceInCount
            preferences[PreferencesKeys.RESONANCE_OUT] = user.resonanceOutCount
        }
    }

    /**
     * Clears all session data. Used during full account deletion or sign out.
     * Purges both local DataStore and persistent Block Store identity markers.
     */
    suspend fun clear() {
        try {
            dataStore.edit { it.clear() }
        } catch (e: Exception) {
            android.util.Log.e("UserSessionManager", "Failed to clear local DataStore", e)
        }
        
        // Purge persistent identity markers from Google Play Services
        blockStoreManager.clear()
    }

    /**
     * Ensures that an anonymous ID exists, saving it if necessary.
     * This should be called from the UI or a Repository at startup, NOT from within userProfile Flow.
     */
    suspend fun ensureAnonymousId() {
        dataStore.edit { preferences ->
            if (preferences[PreferencesKeys.ANONYMOUS_ID] == null) {
                val blockStoreId = blockStoreManager.getAnonymousId()
                val idToUse = blockStoreId ?: UUID.randomUUID().toString()
                preferences[PreferencesKeys.ANONYMOUS_ID] = idToUse
                blockStoreManager.saveAnonymousId(idToUse)
            }
        }
    }
}
