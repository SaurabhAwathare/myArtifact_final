package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.saurabh.artifact.audio.analysis.ReflectionMemory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.memoryDataStore by preferencesDataStore(name = "reflection_memory")

/**
 * Production-hardened repository for persisting reflection memory.
 * Uses DataStore with JSON serialization (Moshi) for complex types like Maps and Lists.
 */
@Singleton
class MemoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    moshi: Moshi
) {
    private val memoryKey = stringPreferencesKey("session_memory")
    
    // Moshi adapter is thread-safe after creation
    private val adapter = moshi.adapter(ReflectionMemory::class.java)

    /**
     * Continuous stream of reflection memory.
     * Safely handles IO exceptions and malformed data.
     */
    val memory: Flow<ReflectionMemory> = context.memoryDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading memory DataStore", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val json = prefs[memoryKey]
            if (json != null) {
                try {
                    adapter.fromJson(json) ?: ReflectionMemory()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse ReflectionMemory JSON", e)
                    ReflectionMemory() // Fallback to safe default
                }
            } else {
                ReflectionMemory()
            }
        }

    /**
     * Atomically updates and persists the reflection memory.
     * Enforces integrity constraints before saving.
     */
    suspend fun saveMemory(memory: ReflectionMemory) = withContext(Dispatchers.IO) {
        try {
            // Integrity Guard: Ensure we don't save invalid values or unbounded lists
            val hardenedMemory = memory.copy(
                recentQuestionIds = memory.recentQuestionIds.take(50), // Hard limit to prevent bloat
                averageDepthPreference = memory.averageDepthPreference.coerceIn(1.0f, 4.0f)
            )

            val json = adapter.toJson(hardenedMemory)
            context.memoryDataStore.edit { prefs ->
                prefs[memoryKey] = json
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ReflectionMemory", e)
            // Note: We don't rethrow to prevent crashing the recording flow's cleanup
        }
    }

    /**
     * One-shot fetch for non-streaming usage (e.g., initialization).
     */
    suspend fun getLatestMemory(): ReflectionMemory {
        return try {
            memory.first()
        } catch (_: Exception) {
            ReflectionMemory()
        }
    }

    companion object {
        private const val TAG = "MemoryRepository"
    }
}
