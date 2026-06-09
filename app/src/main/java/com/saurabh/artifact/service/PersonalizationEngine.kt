package com.saurabh.artifact.service

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appsearch.app.*
import androidx.appsearch.platformstorage.PlatformStorage
import com.saurabh.artifact.model.UserPreferenceDocument
import com.saurabh.artifact.util.OnboardingManager
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
    onboardingManager: OnboardingManager
) {
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val searchSession = MutableStateFlow<AppSearchSession?>(null)

    private val _interactionAffinities = MutableStateFlow<Map<String, Double>>(emptyMap())

    private var isInitializing = false

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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AppSearch", e)
            }
        }
    }

    /**
     * Safe fetch of the user preference profile.
     * Fix for NoSuchElementException: Uses safe map access and nullability checks
     * instead of relying on internal Optional unwrapping which can crash.
     */
    val userProfile: StateFlow<UserPreferenceProfile> = combine(
        onboardingManager.isOnboardingCompleted,
        searchSession.filterNotNull(),
        _interactionAffinities // We can still combine with this for immediate updates
    ) { _, session, inMemoryAffinities ->
        try {
            val result = session.getByDocumentIdAsync(
                GetByDocumentIdRequest.Builder(NAMESPACE)
                    .addIds(PROFILE_ID)
                    .build()
            ).await()

            val documentWrapper = result.successes[PROFILE_ID]
            val document = if (documentWrapper != null) {
                try {
                    documentWrapper.toDocumentClass(UserPreferenceDocument::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to map document to class, likely schema mismatch", e)
                    null
                }
            } else null

            if (document != null) {
                // Calculate affinities from persisted goals if in-memory is empty
                val affinities = if (inMemoryAffinities.isEmpty() && document.goals.isNotEmpty()) {
                    val counts = document.goals.groupingBy { it }.eachCount()
                    val total = document.goals.size.toDouble()
                    counts.mapValues { it.value / total }
                } else inMemoryAffinities

                UserPreferenceProfile(
                    primaryGoal = document.primaryGoal,
                    goals = document.goals?.toSet() ?: emptySet(),
                    dominantEmotion = document.dominantEmotion,
                    affinityScores = affinities
                )
            } else {
                UserPreferenceProfile(affinityScores = inMemoryAffinities)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from AppSearch, returning default profile", e)
            UserPreferenceProfile(affinityScores = inMemoryAffinities)
        }
    }.stateIn(
        scope = engineScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserPreferenceProfile()
    )

    /**
     * Records a new interaction and persists it to AppSearch.
     * @param weight Positive for positive resonance, negative for "Not for me" signals.
     */
    fun recordInteraction(emotion: String, weight: Float = 1.0f) {
        engineScope.launch {
            try {
                val session = searchSession.value ?: return@launch
                val currentProfile = userProfile.value

                // Update goals based on weight
                val updatedEmotions = if (weight > 0) {
                    // Add multiple times for higher weights to increase affinity faster
                    val count = if (weight > 1.0f) 2 else 1
                    val next = currentProfile.goals.toMutableList()
                    repeat(count) { next.add(emotion) }
                    // Keep history capped to prevent unbounded growth
                    if (next.size > 200) next.takeLast(200) else next
                } else {
                    // If negative, we remove instances of this emotion to reduce affinity
                    val list = currentProfile.goals.toMutableList()
                    list.remove(emotion)
                    list
                }
                
                // Calculate dominant emotion from history
                val counts = updatedEmotions.groupingBy { it }.eachCount()
                val newDominantEmotion = counts.maxByOrNull { it.value }?.key
                
                // Update affinity scores (normalized)
                val total = updatedEmotions.size.toDouble()
                val newAffinities = counts.mapValues { it.value / total }
                _interactionAffinities.value = newAffinities

                val doc = UserPreferenceDocument(
                    namespace = NAMESPACE,
                    id = PROFILE_ID,
                    primaryGoal = currentProfile.primaryGoal,
                    goals = updatedEmotions,
                    dominantEmotion = newDominantEmotion,
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
        val emotionMatch = if (artifactEmotion == profile.dominantEmotion) 1.0 else 0.0
        val affinity = profile.affinityScores[artifactEmotion] ?: 0.0
        
        // Weighed towards long-term affinity (0.6) and immediate dominant emotion (0.4)
        return (emotionMatch * 0.4) + (affinity * 0.6)
    }

    companion object {
        private const val TAG = "PersonalizationEngine"
        private const val NAMESPACE = "user_data"
        private const val PROFILE_ID = "current_profile"
    }
}
