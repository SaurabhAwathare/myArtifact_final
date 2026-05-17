package com.saurabh.artifact.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saurabh.artifact.model.AvatarConfig
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
    private val identityEmojiKey = stringPreferencesKey("identity_emoji")
    private val usernameKey = stringPreferencesKey("username")
    private val activeDraftIdKey = stringPreferencesKey("active_draft_id")
    private val activePromptIdKey = stringPreferencesKey("active_prompt_id")
    private val avatarConfigKey = stringPreferencesKey("avatar_config")
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
            val emoji = preferences[identityEmojiKey] ?: "✨"
            val username = preferences[usernameKey] ?: "Anonymous Soul"
            val avatarJson = preferences[avatarConfigKey]
            val avatarConfig = avatarJson?.let {
                try {
                    Json.decodeFromString<AvatarConfig>(it)
                } catch (e: Exception) {
                    null
                }
            }
            UserProfile(id, emoji, username, avatarConfig)
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
     * Updates the user's identity emoji.
     */
    suspend fun updateEmoji(emoji: String) {
        context.sessionDataStore.edit { preferences ->
            preferences[identityEmojiKey] = emoji
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
     * Updates the user's avatar configuration.
     */
    suspend fun updateAvatarConfig(config: AvatarConfig) {
        context.sessionDataStore.edit { preferences ->
            preferences[avatarConfigKey] = Json.encodeToString(config)
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
