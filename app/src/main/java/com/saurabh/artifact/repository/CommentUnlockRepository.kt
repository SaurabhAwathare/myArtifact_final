package com.saurabh.artifact.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.commentUnlockDataStore: DataStore<Preferences> by preferencesDataStore(name = "comment_unlocks")

@Singleton
class CommentUnlockRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val unlockedArtifactsKey = stringSetPreferencesKey("unlocked_artifact_ids")

    /**
     * Returns a flow of the set of artifact IDs that have been unlocked for commenting.
     */
    val unlockedArtifactIds: Flow<Set<String>> = context.commentUnlockDataStore.data
        .map { preferences ->
            preferences[unlockedArtifactsKey] ?: emptySet()
        }

    /**
     * Checks if a specific artifact is unlocked.
     */
    fun isUnlocked(artifactId: String): Flow<Boolean> = unlockedArtifactIds.map { it.contains(artifactId) }

    /**
     * Marks an artifact as unlocked.
     */
    suspend fun unlockArtifact(artifactId: String) {
        context.commentUnlockDataStore.edit { preferences ->
            val current = preferences[unlockedArtifactsKey] ?: emptySet()
            preferences[unlockedArtifactsKey] = current + artifactId
        }
    }
    
    /**
     * Clears all unlocks (useful for debugging or reset).
     */
    suspend fun clearAllUnlocks() {
        context.commentUnlockDataStore.edit { preferences ->
            preferences[unlockedArtifactsKey] = emptySet()
        }
    }
}
