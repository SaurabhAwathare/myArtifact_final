package com.saurabh.artifact.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saurabh.artifact.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore by preferencesDataStore(name = "user_session")

@Singleton
class UserSessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val anonymousIdKey = stringPreferencesKey("anonymous_id")
    private val avatarSeedKey = stringPreferencesKey("avatar_seed")
    private val avatarConfigKey = stringPreferencesKey("avatar_config_json")
    private val usernameKey = stringPreferencesKey("username")
    private val activeDraftIdKey = stringPreferencesKey("active_draft_id")
    private val activePromptIdKey = stringPreferencesKey("active_prompt_id")
    private val artifactEpisodeCounterKey = stringPreferencesKey("artifact_episode_counter")

    /**
     * Retrieves the current anonymous profile. 
     */
    val userProfile: Flow<UserProfile> = context.sessionDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val id = preferences[anonymousIdKey] ?: run {
                val newId = UUID.randomUUID().toString()
                ensureAnonymousId(newId)
                newId
            }
            // Migration logic: if seed is missing, use legacy emoji if present, else generate random
            val seed = preferences[avatarSeedKey] ?: preferences[stringPreferencesKey("identity_emoji")] ?: UUID.randomUUID().toString()
            val username = preferences[usernameKey] ?: com.saurabh.artifact.util.UsernameGenerator.generate()
            val sigil = com.saurabh.artifact.util.UsernameGenerator.deriveSigil(id)
            val configJson = preferences[avatarConfigKey]
            val config = configJson?.let { 
                try {
                    Json.decodeFromString<com.saurabh.artifact.model.AvatarConfig>(it)
                } catch (e: Exception) {
                    com.saurabh.artifact.model.AvatarConfig(seed = seed)
                }
            } ?: com.saurabh.artifact.model.AvatarConfig(seed = seed)
            
            UserProfile(
                anonymousId = id, 
                identityEmoji = "✨", // Deprecated
                username = username, 
                sigil = sigil,
                avatarSeed = seed,
                avatarConfig = config
            )
        }

    val activeDraftId: Flow<String?> = context.sessionDataStore.data
        .map { preferences -> preferences[activeDraftIdKey] }

    val activePromptId: Flow<String?> = context.sessionDataStore.data
        .map { preferences -> preferences[activePromptIdKey] }

    suspend fun setActiveDraftId(id: String?) {
        context.sessionDataStore.edit { preferences ->
            if (id == null) {
                preferences.remove(activeDraftIdKey)
            } else {
                preferences[activeDraftIdKey] = id
            }
        }
    }

    suspend fun setActivePromptId(id: String?) {
        context.sessionDataStore.edit { preferences ->
            if (id == null) {
                preferences.remove(activePromptIdKey)
            } else {
                preferences[activePromptIdKey] = id
            }
        }
    }

    /**
     * Updates the user's avatar seed.
     */
    suspend fun updateAvatarSeed(seed: String) {
        context.sessionDataStore.edit { preferences ->
            preferences[avatarSeedKey] = seed
        }
    }

    /**
     * Updates the user's avatar configuration.
     */
    suspend fun updateAvatarConfig(config: com.saurabh.artifact.model.AvatarConfig) {
        context.sessionDataStore.edit { preferences ->
            preferences[avatarConfigKey] = Json.encodeToString(config)
            preferences[avatarSeedKey] = config.seed
        }
    }

    /**
     * Updates the user's identity username.
     */
    suspend fun updateUsername(username: String) {
        context.sessionDataStore.edit { preferences ->
            preferences[usernameKey] = username
        }
    }

    /**
     * Gets and increments the artifact episode number.
     * This ensures each artifact has a unique, sequential number.
     */
    suspend fun getAndIncrementEpisodeNumber(): Int {
        var currentNumber = 1
        context.sessionDataStore.edit { preferences ->
            val storedValue = preferences[artifactEpisodeCounterKey]?.toIntOrNull() ?: 0
            currentNumber = storedValue + 1
            preferences[artifactEpisodeCounterKey] = currentNumber.toString()
        }
        return currentNumber
    }

    private suspend fun ensureAnonymousId(id: String) {
        context.sessionDataStore.edit { preferences ->
            if (preferences[anonymousIdKey] == null) {
                preferences[anonymousIdKey] = id
            }
        }
    }
}
