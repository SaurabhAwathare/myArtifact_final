package com.saurabh.artifact.service

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow

/**
 * A privacy-first, empathy-driven ranking engine.
 * Prioritizes emotional resonance and recency over addictive engagement metrics.
 */
@Singleton
class FeedRanker @Inject constructor(
    private val personalizationEngine: PersonalizationEngine,
) {

    private val personalizationWeight = 0.30f
    private val moodWeight = 0.20f
    private val styleWeight = 0.15f // New: Conversational Style affinity
    private val qualityWeight = 0.15f
    private val recencyWeight = 0.15f
    private val explorationWeight = 0.05f

    /**
     * Ranks a list of candidate artifacts based on resonance and diversity.
     */
    suspend fun rank(artifacts: List<Artifact>, user: User?, currentMood: String?): List<Artifact> = withContext(Dispatchers.Default) {
        if (artifacts.isEmpty()) return@withContext emptyList<Artifact>()

        val scoredList = artifacts.map { artifact ->
            val score = calculateScore(artifact, user, currentMood)
            artifact to score
        }

        // 1. Initial Sort by score
        val sorted = scoredList.asSequence()
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()

        // 2. Apply Diversity Filters (Author, Emotion, and Style Pacing)
        applyDiversity(sorted)
    }

    private fun calculateScore(artifact: Artifact, user: User?, currentMood: String?): Double {
        val profile = personalizationEngine.userProfile.value
        val personalizationScore = personalizationEngine.scoreContent(artifact.emotion, profile)
        
        val moodScore = calculateMoodMatch(artifact, user, currentMood)
        val styleScore = calculateStyleMatch(artifact, profile) // New
        val qualityScore = calculateQualityScore(artifact)
        val recencyScore = calculateRecencyScore(artifact)
        val explorationBoost = if (artifact.playCount < 10) 1.0 else 0.0

        return (personalizationScore * personalizationWeight) +
               (moodScore * moodWeight) +
               (styleScore * styleWeight) +
               (qualityScore * qualityWeight) + 
               (recencyScore * recencyWeight) +
               (explorationBoost * explorationWeight)
    }

    /**
     * Higher score if the artifact's conversational style matches user affinity.
     */
    private fun calculateStyleMatch(artifact: Artifact, profile: UserPreferenceProfile): Double {
        val styleLabel = artifact.conversationMetadata.primaryStyle?.name ?: return 0.5
        return profile.affinityScores[styleLabel] ?: 0.5
    }

    /**
     * Higher score if the artifact matches the user's active mood mode.
     */
    private fun calculateMoodMatch(artifact: Artifact, user: User?, currentMood: String?): Double {
        val targetEmotion = currentMood ?: user?.dominantEmotion ?: user?.deriveDominantEmotion() ?: return 0.5
        
        return if (artifact.emotion.equals(targetEmotion, ignoreCase = true)) {
            1.0
        } else {
            0.2
        }
    }

    /**
     * Estimates content quality using resonance signals (reactions/plays ratio).
     */
    private fun calculateQualityScore(artifact: Artifact): Double {
        val plays = artifact.playCount.toDouble().coerceAtLeast(1.0)
        val reactions = artifact.reactionCount.toDouble()
        
        // Resonance ratio: how many people felt enough to react?
        val ratio = (reactions / plays).coerceIn(0.0, 1.0)
        
        // Add a small log-scaled boost for overall volume to favor proven content slightly
        val volumeBoost = ln(plays + 1.0) / 10.0
        
        return (ratio * 0.8) + (volumeBoost * 0.2)
    }

    /**
     * Recency decay: fresh content is prioritized.
     */
    private fun calculateRecencyScore(artifact: Artifact): Double {
        val now = System.currentTimeMillis()
        val created = artifact.createdAt.toDate().time
        val ageInHours = TimeUnit.MILLISECONDS.toHours(now - created).toDouble()
        
        // 1 / (1 + age^0.8) provides a balance of freshness and staying power
        return 1.0 / (1.0 + ageInHours.pow(0.8))
    }

    /**
     * Ensures the feed is diverse in both authors and emotions.
     */
    private fun applyDiversity(sorted: List<Artifact>): List<Artifact> {
        if (sorted.size < 4) return sorted
        
        val diversified = mutableListOf<Artifact>()
        val remaining = sorted.toMutableList()
        
        val recentUserIds = mutableSetOf<String>()
        val recentEmotions = mutableListOf<String>()

        while (remaining.isNotEmpty()) {
            // Priority 1: Satisfy both author and emotion diversity
            var next = remaining.find { artifact ->
                val isRecentAuthor = artifact.userId in recentUserIds
                val isEmotionClustered = recentEmotions.count { it == artifact.emotion } >= 2
                !isRecentAuthor && !isEmotionClustered
            }

            // Priority 2: Satisfy at least emotion diversity (more important for echo chambers)
            if (next == null) {
                next = remaining.find { artifact ->
                    val isEmotionClustered = recentEmotions.count { it == artifact.emotion } >= 2
                    !isEmotionClustered
                }
            }

            // Fallback: Take the highest ranked remaining
            if (next == null) {
                next = remaining.first()
            }
            
            diversified.add(next)
            remaining.remove(next)
            
            // Update tracking
            recentUserIds.add(next.userId)
            if (diversified.size > 3) {
                recentUserIds.remove(diversified[diversified.size - 4].userId)
            }
            
            recentEmotions.add(next.emotion)
            if (recentEmotions.size > 3) {
                recentEmotions.removeAt(0)
            }
        }
        
        return diversified
    }
}
