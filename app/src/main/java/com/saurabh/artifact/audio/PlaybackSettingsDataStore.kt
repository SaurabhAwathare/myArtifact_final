package com.saurabh.artifact.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_settings")

@Singleton
class PlaybackSettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object PreferencesKeys {
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SKIP_SILENCE_ENABLED = booleanPreferencesKey("skip_silence_enabled")
        val LAST_ARTIFACT_ID = stringPreferencesKey("last_artifact_id")
        val CURRENT_QUEUE_IDS = stringPreferencesKey("current_queue_ids")
        val CURRENT_QUEUE_INDEX = androidx.datastore.preferences.core.intPreferencesKey("current_queue_index")
    }

    val playbackSpeed: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYBACK_SPEED] ?: 1.0f
    }

    val skipSilenceEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SKIP_SILENCE_ENABLED] ?: false
    }

    val lastArtifactId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_ARTIFACT_ID]
    }

    val currentQueueIds: Flow<List<String>> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.CURRENT_QUEUE_IDS]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    }

    val currentQueueIndex: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.CURRENT_QUEUE_INDEX] ?: 0
    }

    suspend fun updatePlaybackSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYBACK_SPEED] = speed
        }
    }

    suspend fun updateSkipSilenceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SKIP_SILENCE_ENABLED] = enabled
        }
    }

    suspend fun updateLastArtifactId(artifactId: String?) {
        context.dataStore.edit { preferences ->
            if (artifactId == null) {
                preferences.remove(PreferencesKeys.LAST_ARTIFACT_ID)
            } else {
                preferences[PreferencesKeys.LAST_ARTIFACT_ID] = artifactId
            }
        }
    }

    suspend fun updateQueue(ids: List<String>, index: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CURRENT_QUEUE_IDS] = ids.joinToString(",")
            preferences[PreferencesKeys.CURRENT_QUEUE_INDEX] = index
        }
    }
}
