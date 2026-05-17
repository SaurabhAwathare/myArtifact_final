package com.saurabh.artifact.service

import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.FeedRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedComposer @Inject constructor(
    private val repository: FeedRepository,
    private val pacingEngine: PacingEngine
) {

    /**
     * Composes a personalized feed for the user.
     */
    suspend fun composeFeed(userId: String): List<FeedArtifact> = coroutineScope {
        val followedJob = async { repository.getFollowedArtifacts(userId) }
        val unfinishedJob = async { repository.getUnfinishedSessions(userId) }
        val discoveryJob = async { repository.getDiscoveryCandidates() }
        val profileJob = async { repository.getEmotionalProfile(userId) }

        val followed = followedJob.await()
        val unfinishedSessions = unfinishedJob.await()
        val discovery = discoveryJob.await()
        val profile = profileJob.await() ?: EmotionalCompatibilityProfile(userId = userId)

        // 1. Map Unfinished to FeedArtifacts
        val unfinishedItems = unfinishedSessions.mapNotNull { session ->
            val artifact = discovery.find { it.id == session.artifactId } ?: return@mapNotNull null
            FeedArtifact(
                artifact = artifact,
                reason = FeedRecommendationReason.CONTINUE_LISTENING,
                isUnfinished = true,
                lastPositionMs = session.lastPositionMs
            )
        }

        // 2. Map Followed to FeedArtifacts
        val followedItems = followed.map { artifact ->
            FeedArtifact(
                artifact = artifact,
                reason = FeedRecommendationReason.FOLLOWED_CREATOR,
                compatibilityScore = calculateEmotionalAlignment(artifact, profile)
            )
        }

        // 3. Map Discovery to FeedArtifacts
        val discoveryItems = discovery
            .filter { disc -> followed.none { it.id == disc.id } && unfinishedSessions.none { it.artifactId == disc.id } }
            .map { artifact ->
                val alignment = calculateEmotionalAlignment(artifact, profile)
                FeedArtifact(
                    artifact = artifact,
                    reason = if (alignment > 0.7f) FeedRecommendationReason.EMOTIONAL_RESONANCE else FeedRecommendationReason.DISCOVERY,
                    compatibilityScore = alignment
                )
            }

        // 4. Blend & Rank
        val combined = (unfinishedItems + followedItems + discoveryItems)
            .sortedWith(compareByDescending<FeedArtifact> { it.isUnfinished }
                .thenByDescending { it.reason == FeedRecommendationReason.FOLLOWED_CREATOR }
                .thenByDescending { it.compatibilityScore }
            )

        // 5. Apply Emotional Pacing
        pacingEngine.paceFeed(combined)
    }

    private fun calculateEmotionalAlignment(artifact: Artifact, profile: EmotionalCompatibilityProfile): Float {
        if (profile.preferredEmotions.isEmpty()) return 0.5f
        return if (profile.preferredEmotions.contains(artifact.emotion)) 0.9f else 0.3f
    }
}
