package com.saurabh.artifact.service

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appsearch.app.*
import androidx.appsearch.platformstorage.PlatformStorage
import com.saurabh.artifact.model.UserPreferenceDocument
import com.saurabh.artifact.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class UserPreferenceProfile(
    val primaryGoal: String? = null,
    val goals: Set<String> = emptySet(),
    val dominantEmotion: String? = null,
    val affinityScores: Map<String, Double> = emptyMap()
)

/**
 * Production-ready engine for local personalization using AppSearch.
 * Handles the "Failed to fetch from AppSearch" crash by safely checking result existence.
 */
@Singleton
class PersonalizationEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository
) {
    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val searchSession = MutableStateFlow<AppSearchSession?>(null)

    private val _userProfile = MutableStateFlow(UserPreferenceProfile())
    val userProfile: StateFlow<UserPreferenceProfile> = _userProfile.asStateFlow()

    private val dataCollectionConsent = settingsRepository.userSettings
        .map { it.dataCollectionConsent }
        .distinctUntilChanged()
        .stateIn(engineScope, SharingStarted.Eagerly, false)

    private var isInitializing = false

    init {
        // Monitor consent changes to trigger cleanup
        // Note: collectLatest is used to ensure we only process the most recent state
        engineScope.launch {
            dataCollectionConsent.collectLatest { consent ->
                if (!consent) {
                    clearLocalData()
                }
            }
        }
    }

    /**
     * Safety check for initialization. Can be called by clients to ensure
     * search capability is ready without blocking the main thread.
     */
    suspend fun ensureInitialized() {
        if (searchSession.value != null) return
        
        if (!isInitializing) {
            initialize()
        }
        
        // Wait for session to be ready
        searchSession.filterNotNull().first()
    }

    /**
     * Deterministic initialization of AppSearch.
     * Called during the phased startup to prevent allocation bursts.
     */
    fun initialize() {
        if (isInitializing || searchSession.value != null) return
        isInitializing = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            initializeAppSearch()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun initializeAppSearch() {
        engineScope.launch {
            try {
                val session = PlatformStorage.createSearchSessionAsync(
                    PlatformStorage.SearchContext.Builder(context, "personalization_db").build()
                ).await()
                
                // Set schema for our document
                session.setSchemaAsync(
                    SetSchemaRequest.Builder()
                        .addDocumentClasses(UserPreferenceDocument::class.java)
                        .setForceOverride(false)
                        .build()
                ).await()

                searchSession.value = session
                Log.d(TAG, "AppSearch initialized successfully.")

                // Load initial profile from disk once
                loadProfileFromDisk(session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AppSearch", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun loadProfileFromDisk(session: AppSearchSession) {
        if (!dataCollectionConsent.value) {
            Log.d(TAG, "Skipping profile load: Data collection consent not granted.")
            return
        }
        try {
            val result = session.getByDocumentIdAsync(
                GetByDocumentIdRequest.Builder(NAMESPACE)
                    .addIds(PROFILE_ID)
                    .build()
            ).await()

            val documentWrapper = result.successes[PROFILE_ID]
            val document = documentWrapper?.toDocumentClass(UserPreferenceDocument::class.java)

            if (document != null) {
                val counts = document.goals.groupingBy { it }.eachCount()
                val total = document.goals.size.toDouble()
                val affinities = counts.mapValues { it.value / total }

                _userProfile.value = UserPreferenceProfile(
                    primaryGoal = document.primaryGoal,
                    goals = document.goals.toSet(),
                    dominantEmotion = document.dominantEmotion,
                    affinityScores = affinities
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial profile from AppSearch", e)
        }
    }

    /**
     * Records a new interaction and persists it to AppSearch.
     * @param weight Positive for positive resonance, negative for "Not for me" signals.
     */
    fun recordInteraction(emotion: String, weight: Float = 1.0f) {
        val hasConsent = dataCollectionConsent.value
        Log.d(TAG, "recordInteraction: emotion=$emotion, weight=$weight, hasConsent=$hasConsent")
        if (!hasConsent) {
            return
        }
        
        val currentProfile = _userProfile.value

        // Update goals based on weight
        val updatedEmotions = if (weight > 0) {
            val count = if (weight > 1.0f) 2 else 1
            val next = currentProfile.goals.toMutableList()
            repeat(count) { next.add(emotion) }
            if (next.size > 200) next.takeLast(200) else next
        } else {
            val list = currentProfile.goals.toMutableList()
            list.remove(emotion)
            list
        }
        
        val counts = updatedEmotions.groupingBy { it }.eachCount()
        val newDominantEmotion = counts.maxByOrNull { it.value }?.key
        val total = updatedEmotions.size.toDouble()
        val newAffinities = counts.mapValues { it.value / total }

        val newProfile = currentProfile.copy(
            goals = updatedEmotions.toSet(),
            dominantEmotion = newDominantEmotion,
            affinityScores = newAffinities
        )
        
        // Update memory immediately
        _userProfile.value = newProfile

        // Persist to disk asynchronously on IO
        persistProfileToDisk(newProfile)
    }

    private fun persistProfileToDisk(profile: UserPreferenceProfile) {
        engineScope.launch(Dispatchers.IO) {
            try {
                val session = searchSession.value ?: return@launch
                
                val doc = UserPreferenceDocument(
                    namespace = NAMESPACE,
                    id = PROFILE_ID,
                    primaryGoal = profile.primaryGoal,
                    goals = profile.goals.toList(),
                    dominantEmotion = profile.dominantEmotion,
                    lastInteractionTimestamp = System.currentTimeMillis()
                )

                session.putAsync(PutDocumentsRequest.Builder().addDocuments(doc).build()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist interaction to AppSearch", e)
            }
        }
    }

    /**
     * Records a detailed interaction based on listening behavior.
     */
    fun recordDetailedInteraction(
        emotion: String,
        completionRate: Float,
        wasSkipped: Boolean = false,
        isReplay: Boolean = false
    ) {
        val weight = when {
            isReplay -> 1.2f
            completionRate >= 0.8f -> 1.0f
            wasSkipped && completionRate < 0.2f -> -0.5f
            else -> 0f
        }
        
        if (weight != 0f) {
            recordInteraction(emotion, weight)
        }
    }

    /**
     * Scores content based on the current personalized profile.
     */
    fun scoreContent(artifactEmotion: String, profile: UserPreferenceProfile): Double {
        if (!dataCollectionConsent.value) return 0.5

        val emotionMatch = if (artifactEmotion == profile.dominantEmotion) 1.0 else 0.0
        val affinity = profile.affinityScores[artifactEmotion] ?: 0.0
        
        // Weighed towards long-term affinity (0.6) and immediate dominant emotion (0.4)
        return (emotionMatch * 0.4) + (affinity * 0.6)
    }

    /**
     * Clears all local personalization data from AppSearch and memory.
     */
    fun clearLocalData() {
        _userProfile.value = UserPreferenceProfile()
        
        engineScope.launch(Dispatchers.IO) {
            try {
                val session = searchSession.value ?: return@launch
                session.removeAsync(
                    RemoveByDocumentIdRequest.Builder(NAMESPACE)
                        .addIds(PROFILE_ID)
                        .build()
                ).await()
                Log.d(TAG, "Local personalization data cleared.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear personalization data from AppSearch", e)
            }
        }
    }

    companion object {
        private const val TAG = "PersonalizationEngine"
        private const val NAMESPACE = "user_data"
        private const val PROFILE_ID = "current_profile"
    }
}
