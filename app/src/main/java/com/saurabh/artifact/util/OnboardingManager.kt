package com.saurabh.artifact.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

import androidx.datastore.preferences.core.stringSetPreferencesKey

private val Context.dataStore by preferencesDataStore(name = "onboarding_prefs")

@Singleton
class OnboardingManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val onboardingCompletedKey = booleanPreferencesKey("onboarding_completed")
    private val userGoalsKey = stringSetPreferencesKey("user_goals")

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

    suspend fun setOnboardingCompleted(goals: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[onboardingCompletedKey] = true
            preferences[userGoalsKey] = goals
        }
    }
}
