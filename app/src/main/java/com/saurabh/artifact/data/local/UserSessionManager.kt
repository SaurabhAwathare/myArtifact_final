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
        val AVATAR_SEED = stringPreferencesKey("avatar_seed")
        val AVATAR_CONFIG_JSON = stringPreferencesKey("avatar_config_json")
        val AVATAR_COLOR = stringPreferencesKey("avatar_color")
        val USERNAME = stringPreferencesKey("username")
        val SIGIL = stringPreferencesKey("sigil")
        val IS_ANONYMOUS = booleanPreferencesKey("is_anonymous")
        val RESONANCE_IN = longPreferencesKey("resonance_in")
        val RESONANCE_OUT = longPreferencesKey("resonance_out")
        val ACTIVE_DRAFT_ID = stringPreferencesKey("active_draft_id")
        val ACTIVE_PROMPT_ID = stringPreferencesKey("active_prompt_id")
        
        // Legacy/Migration keys
        val IDENTITY_EMOJI = stringPreferencesKey("identity_emoji")
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
            
            val seed = preferences[PreferencesKeys.AVATAR_SEED] 
                ?: preferences[PreferencesKeys.IDENTITY_EMOJI] 
                ?: UUID.randomUUID().toString()
                
            val username = preferences[PreferencesKeys.USERNAME] ?: com.saurabh.artifact.util.UsernameGenerator.generate()
            val sigil = preferences[PreferencesKeys.SIGIL] ?: com.saurabh.artifact.util.UsernameGenerator.deriveSigil(id)
            val avatarColor = preferences[PreferencesKeys.AVATAR_COLOR] ?: "#FFD700"
            val isAnonymous = preferences[PreferencesKeys.IS_ANONYMOUS] ?: true
            val resonanceIn = preferences[PreferencesKeys.RESONANCE_IN] ?: 0L
            val resonanceOut = preferences[PreferencesKeys.RESONANCE_OUT] ?: 0L
            
            val configJson = preferences[PreferencesKeys.AVATAR_CONFIG_JSON]
            val config = configJson?.let { 
                try {
                    Json.decodeFromString<com.saurabh.artifact.model.AvatarConfig>(it).copy(seed = seed)
                } catch (_: Exception) {
                    com.saurabh.artifact.model.AvatarConfig(seed = seed, theme = "CARTOON")
                }
            } ?: com.saurabh.artifact.model.AvatarConfig(seed = seed, theme = "CARTOON")
            
            UserProfile(
                anonymousId = id, 
                username = username, 
                sigil = sigil,
                avatarSeed = seed,
                avatarColor = avatarColor,
                avatarConfig = config,
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
     * Updates the user's avatar configuration.
     */
    suspend fun updateAvatarConfig(config: com.saurabh.artifact.model.AvatarConfig) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AVATAR_CONFIG_JSON] = Json.encodeToString(config)
            preferences[PreferencesKeys.AVATAR_SEED] = config.seed
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
            preferences[PreferencesKeys.AVATAR_SEED] = user.avatarSeed
            preferences[PreferencesKeys.AVATAR_COLOR] = user.avatarColor
            preferences[PreferencesKeys.AVATAR_CONFIG_JSON] = Json.encodeToString(user.avatarConfig)
            preferences[PreferencesKeys.IS_ANONYMOUS] = user.isAnonymous
            preferences[PreferencesKeys.RESONANCE_IN] = user.resonanceInCount
            preferences[PreferencesKeys.RESONANCE_OUT] = user.resonanceOutCount
        }
    }

    /**
     * Clears all session data. Used during sign out.
     */
    suspend fun clear() {
        dataStore.edit { it.clear() }
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
