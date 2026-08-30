package com.saurabh.artifact.service

import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.FeedRepository
import com.saurabh.artifact.repository.PaginatedArtifacts
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedComposer @Inject constructor(
    private val repository: FeedRepository,
    private val pacingEngine: PacingEngine,
) {

    /**
     * Composes a personalized feed for the user.
     */
    suspend fun composeFeed(userId: String): List<FeedArtifact> = coroutineScope {
        val resonatedJob = async { repository.getResonatingArtifacts(userId) }
        val discoveryJob = async { repository.getDiscoveryCandidates(userId) }
        val profileJob = async { repository.getEmotionalProfile(userId) }

        val resonated = resonatedJob.await().getOrDefault(PaginatedArtifacts(emptyList(), null))
        val discovery = discoveryJob.await().getOrDefault(PaginatedArtifacts(emptyList(), null))
        val profile = profileJob.await().getOrElse { EmotionalCompatibilityProfile(userId = userId) }

        // 1. Map Resonated to FeedArtifacts
        val resonatedItems = resonated.artifacts.map { artifact ->
            FeedArtifact(
                artifact = artifact,
                reason = FeedRecommendationReason.RESONATING_PRESENCE,
                compatibilityScore = calculateEmotionalAlignment(artifact, profile)
            )
        }

        // 2. Map Discovery to FeedArtifacts
        val discoveryItems = discovery.artifacts
            .filter { disc -> resonated.artifacts.none { it.id == disc.id } }
            .map { artifact ->
                val alignment = calculateEmotionalAlignment(artifact, profile)
                FeedArtifact(
                    artifact = artifact,
                    reason = if (alignment > 0.7f) FeedRecommendationReason.EMOTIONAL_RESONANCE else FeedRecommendationReason.DISCOVERY,
                    compatibilityScore = alignment
                )
            }

        // 3. Blend & Rank
        val combined = (resonatedItems + discoveryItems)
            .sortedWith(
                compareByDescending<FeedArtifact> { 
                    it.reason == FeedRecommendationReason.RESONATING_PRESENCE 
                }.thenByDescending { it.compatibilityScore }
            )

        // 4. Apply Emotional Pacing
        pacingEngine.paceFeed(combined)
    }

    private fun calculateEmotionalAlignment(artifact: Artifact, profile: EmotionalCompatibilityProfile): Float {
        if (profile.preferredEmotions.isEmpty()) return 0.5f
        return if (profile.preferredEmotions.contains(artifact.emotion)) 0.9f else 0.3f
    }
}
