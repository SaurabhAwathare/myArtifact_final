package com.saurabh.artifact.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class DebugSettings(
    val useMockTopics: Boolean = false,
    val showDebugOverlays: Boolean = false,
    val bypassReview: Boolean = false
)

@Singleton
class DebugRepository @Inject constructor(
    @Named("debugDataStore") private val dataStore: DataStore<Preferences>
) {
    private val useMockTopicsKey = booleanPreferencesKey("use_mock_topics")
    private val showDebugOverlaysKey = booleanPreferencesKey("show_debug_overlays")
    private val bypassReviewKey = booleanPreferencesKey("bypass_review")

    val debugSettings: Flow<DebugSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            DebugSettings(
                useMockTopics = preferences[useMockTopicsKey] ?: false,
                showDebugOverlays = preferences[showDebugOverlaysKey] ?: false,
                bypassReview = preferences[bypassReviewKey] ?: false
            )
        }

    suspend fun updateUseMockTopics(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[useMockTopicsKey] = enabled
        }
    }

    suspend fun updateShowDebugOverlays(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[showDebugOverlaysKey] = enabled
        }
    }

    suspend fun updateBypassReview(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[bypassReviewKey] = enabled
        }
    }
}
