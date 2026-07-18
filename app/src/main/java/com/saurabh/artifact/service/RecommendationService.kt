package com.saurabh.artifact.service

import com.google.firebase.Timestamp
import com.saurabh.artifact.model.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A stateless pipeline-based recommendation engine for Artifact.
 * 
 * The service processes candidates through multiple stages:
 * 1. Eligibility & Safety (Filtering)
 * 2. Freshness (Categorization)
 * 3. Exploration (Identification)
 * 4. Ranking (Local Sorting)
 * 5. Diversity (Post-processing)
 */
@Singleton
class RecommendationService @Inject constructor() {

    /**
     * Ranks and filters a list of artifacts through the recommendation pipeline.
     */
    fun rank(
        candidates: List<Artifact>,
        config: RecommendationConfig = RecommendationConfig.DEFAULT
    ): List<Artifact> {
        if (candidates.isEmpty()) return emptyList()

        // 1. Eligibility & Safety Stage
        val safeCandidates = candidates.filter { isEligible(it) }

        // 2. Categorization Stage (Freshness & Exploration)
        val now = Timestamp.now()
        val freshWindowMillis = TimeUnit.HOURS.toMillis(config.freshnessWindowHours.toLong())
        val standardWindowMillis = TimeUnit.DAYS.toMillis(config.standardWindowDays.toLong())

        val freshPool = mutableListOf<Artifact>()
        val standardPool = mutableListOf<Artifact>()
        val evergreenPool = mutableListOf<Artifact>()
        val underHeardPool = mutableListOf<Artifact>()

        for (artifact in safeCandidates) {
            val ageMillis = now.toDate().time - artifact.createdAt.toDate().time
            
            // Check if under-heard (for Exploration stage)
            if (artifact.playCount < config.underHeardThreshold) {
                underHeardPool.add(artifact)
                continue // Under-heard voices get their own priority pool
            }

            when {
                ageMillis < freshWindowMillis -> freshPool.add(artifact)
                ageMillis < standardWindowMillis -> standardPool.add(artifact)
                else -> evergreenPool.add(artifact)
            }
        }

        // 3. Quality Stage (Sorting within pools)
        val sortedFresh = sortByQuality(freshPool, config)
        val sortedStandard = sortByQuality(standardPool, config)
        val sortedEvergreen = sortByQuality(evergreenPool, config)
        val sortedUnderHeard = sortByQuality(underHeardPool, config)

        // 4. Merge Stage (Applying Exploration Ratio)
        val mergedFeed = mergePools(
            mainPools = listOf(sortedFresh, sortedStandard, sortedEvergreen),
            explorationPool = sortedUnderHeard,
            explorationRatio = config.explorationRatio
        )

        // 5. Diversity Stage (Post-processing)
        return applyDiversity(mergedFeed, config.maxConsecutiveFromCreator)
    }

    private fun isEligible(artifact: Artifact): Boolean {
        return artifact.isPublic &&
                artifact.status == ArtifactStatus.ACTIVE &&
                artifact.recommendationState == RecommendationState.ACTIVE &&
                artifact.moderation.status == ModerationStatus.SAFE &&
                artifact.audioUrl.isNotEmpty()
    }

    private fun sortByQuality(artifacts: List<Artifact>, config: RecommendationConfig): List<Artifact> {
        return artifacts.sortedWith(compareByDescending<Artifact> {
            // Recency score (normalized by a week)
            val recency = it.createdAt.seconds.toFloat() / (7 * 24 * 3600)
            // Quality score (resonance count)
            val quality = it.reactionCount.toFloat()
            
            (recency * config.recencyWeight) + (quality * config.resonanceWeight)
        })
    }

    private fun mergePools(
        mainPools: List<List<Artifact>>,
        explorationPool: List<Artifact>,
        explorationRatio: Float
    ): List<Artifact> {
        val result = mutableListOf<Artifact>()
        val mainIterators = mainPools.map { it.iterator() }
        val explorationIterator = explorationPool.iterator()
        
        var artifactsSinceLastExploration = 0
        val explorationInterval = if (explorationRatio > 0) (1 / explorationRatio).toInt() else Int.MAX_VALUE

        while (mainIterators.any { it.hasNext() } || explorationIterator.hasNext()) {
            // Inject exploration artifact if interval reached
            if (artifactsSinceLastExploration >= explorationInterval && explorationIterator.hasNext()) {
                result.add(explorationIterator.next())
                artifactsSinceLastExploration = 0
                continue
            }

            // Otherwise, pick from main pools in order (Fresh -> Standard -> Evergreen)
            var found = false
            for (i in mainIterators.indices) {
                val iter = mainIterators[i]
                if (iter.hasNext()) {
                    result.add(iter.next())
                    artifactsSinceLastExploration++
                    found = true
                    break
                }
            }

            // If main pools are empty but exploration still has items, fill them in
            if (!found && explorationIterator.hasNext()) {
                result.add(explorationIterator.next())
            } else if (!found) {
                break
            }
        }
        
        return result
    }

    private fun applyDiversity(artifacts: List<Artifact>, maxConsecutive: Int): List<Artifact> {
        if (artifacts.isEmpty()) return emptyList()
        
        val result = mutableListOf<Artifact>()
        val pending = artifacts.toMutableList()
        
        var lastCreatorId = ""
        var consecutiveCount = 0

        while (pending.isNotEmpty()) {
            var foundCandidate = false
            for (i in pending.indices) {
                val artifact = pending[i]
                val creatorId = artifact.userId
                
                val isSameCreator = creatorId == lastCreatorId
                if (!isSameCreator || consecutiveCount < maxConsecutive) {
                    result.add(artifact)
                    pending.removeAt(i)
                    
                    if (isSameCreator) {
                        consecutiveCount++
                    } else {
                        lastCreatorId = creatorId
                        consecutiveCount = 1
                    }
                    foundCandidate = true
                    break
                }
            }
            
            // If we can't find any candidate that satisfies diversity (all remaining are same creator)
            // just append them to the end (fallback)
            if (!foundCandidate) {
                result.addAll(pending)
                break
            }
        }
        
        return result
    }
}
