package com.saurabh.artifact.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "onboarding_prefs")

@Singleton
class OnboardingManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val onboardingCompletedKey = booleanPreferencesKey("onboarding_completed")
    private val legacyMnemonicSavedKey = booleanPreferencesKey("is_mnemonic_saved")
    private val userGoalsKey = stringSetPreferencesKey("user_goals")

    private fun userMnemonicSavedKey(uid: String) = booleanPreferencesKey("is_mnemonic_saved_$uid")

    val isOnboardingCompleted: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[onboardingCompletedKey] ?: false
        }

    fun isMnemonicSaved(uid: String): Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            if (preferences[userMnemonicSavedKey(uid)] != null) {
                preferences[userMnemonicSavedKey(uid)] ?: false
            } else {
                val hasAnyUidKey = preferences.asMap().keys.any { it.name.startsWith("is_mnemonic_saved_") }
                if (!hasAnyUidKey) {
                    preferences[legacyMnemonicSavedKey] ?: false
                } else {
                    false
                }
            }
        }

    val isMnemonicSaved: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[legacyMnemonicSavedKey] ?: false }

    suspend fun setMnemonicSaved(uid: String, saved: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[userMnemonicSavedKey(uid)] = saved
            preferences[legacyMnemonicSavedKey] = saved
        }
    }

    suspend fun setMnemonicSaved(saved: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[legacyMnemonicSavedKey] = saved
        }
    }

    suspend fun deleteMnemonicSavedForUser(uid: String) {
        context.dataStore.edit { preferences ->
            preferences.remove(userMnemonicSavedKey(uid))
        }
    }

    suspend fun setOnboardingCompleted(goals: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[onboardingCompletedKey] = true
            preferences[userGoalsKey] = goals
        }
    }

    /**
     * Clears transient user session state on logout while preserving persistent recovery completion flags.
     */
    suspend fun clearUserSessionData() {
        context.dataStore.edit { preferences ->
            preferences.remove(userGoalsKey)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
