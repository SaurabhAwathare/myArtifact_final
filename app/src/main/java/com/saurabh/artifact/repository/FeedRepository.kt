package com.saurabh.artifact.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.model.*
import com.saurabh.artifact.service.RecommendationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PaginatedArtifacts(
    val artifacts: List<Artifact>,
    val lastVisible: DocumentSnapshot?,
)

@Singleton
class FeedRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val recommendationService: RecommendationService,
    private val visibilityFilter: com.saurabh.artifact.domain.ArtifactVisibilityFilter,
    private val safetyPolicy: com.saurabh.artifact.domain.SafetyPolicy,
    private val diagnosticLogger: DiagnosticLogger
) {

    private companion object {
        /**
         * The maximum number of followed users to scan for the Resonating Feed.
         * Bounded optimization to prevent O(N) query growth while preserving meaningful connection.
         */
        const val MAX_RESONANCE_SCAN = 50
    }

    suspend fun getResonatingArtifacts(
        userId: String, 
        limit: Int = 20,
        lastVisible: DocumentSnapshot? = null,
        emotion: String? = null
    ): Result<PaginatedArtifacts> = withContext(Dispatchers.IO) {
        return@withContext try {
            val relatedEmotions = if (!emotion.isNullOrEmpty() && emotion != "All") {
                com.saurabh.artifact.util.EmotionCategoryMapper.getRelatedEmotions(emotion)
            } else null

            // Phase 3: Bounded Discovery
            // Fetch only the 50 most recent follows to keep query count stable
            val resonatedUserIds: List<String> = firestore.collection("users")
                .document(userId)
                .collection("resonance_out")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(MAX_RESONANCE_SCAN.toLong())
                .get()
                .await()
                .documents
                .map { it.id }

            if (resonatedUserIds.isEmpty()) return@withContext Result.success(PaginatedArtifacts(emptyList(), null))

            val suppressedIds = visibilityFilter.getSuppressedIdsSnapshot(userId)
            val ignoredUserIds = visibilityFilter.getIgnoredUserIdsSnapshot()

            val chunks = resonatedUserIds.chunked(10)
            val allArtifacts = mutableListOf<Artifact>()
            var lastDocInBatch: DocumentSnapshot? = null

            for (chunk in chunks) {
                var query = firestore.collection("artifacts")
                    .whereIn("userId", chunk)
                    .whereEqualTo("isPublic", true)
                    .whereEqualTo("status", ArtifactStatus.ACTIVE.name)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit.toLong())
                
                lastVisible?.let {
                    query = query.startAfter(it)
                }

                val snapshot = query.get().await()
                
                val mappedChunk = snapshot.documents.mapNotNull { doc ->
                    val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                    if ((artifact == null) || artifact.audioUrl.isEmpty()) return@mapNotNull null
                    
                    // Filter by emotion locally if requested (Firestore limit: only one whereIn per query)
                    if (relatedEmotions != null && !relatedEmotions.contains(artifact.emotion)) {
                        return@mapNotNull null
                    }

                    val reportCount = doc.getLong("reportCount") ?: 0L
                    val safetyConcernCount = doc.getLong("safetyConcernCount") ?: 0L
                    
                    val artifactSnapshot = artifact.copy(
                        reportCount = reportCount,
                        safetyConcernCount = safetyConcernCount,
                        reporterIds = emptyList() // Deprecated
                    )

                    val isEligible = safetyPolicy.isEligibleForDiscovery(
                        artifact = artifactSnapshot,
                        currentUserId = userId,
                        isSuppressedByUser = suppressedIds.contains(doc.id),
                        ignoredUserIds = ignoredUserIds
                    )
                    
                    if (isEligible) {
                        artifactSnapshot
                    } else {
                        null
                    }
                }
                allArtifacts.addAll(mappedChunk)
                
                if (snapshot.documents.isNotEmpty()) {
                    val currentLast = snapshot.documents.last()
                    if (lastDocInBatch == null || (currentLast.getTimestamp("createdAt") ?: Timestamp.now()) < (lastDocInBatch.getTimestamp("createdAt") ?: Timestamp.now())) {
                        lastDocInBatch = currentLast
                    }
                }
            }
            
            val sorted = allArtifacts.asSequence().sortedByDescending { it.createdAt }.take(limit).toList()
            Result.success(PaginatedArtifacts(sorted, lastDocInBatch))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FEED, "FEED_RESONATING_FETCH_FAILED", throwable = e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Fetches discovery candidates based on emotional compatibility with pagination.
     * Integrated with RecommendationService for ranking and diversity.
     */
    suspend fun getDiscoveryCandidates(
        userId: String? = null,
        limit: Int = 20,
        lastVisible: DocumentSnapshot? = null,
        emotion: String? = null
    ): Result<PaginatedArtifacts> = withContext(Dispatchers.IO) {
        return@withContext try {
            val poolSize = 50 // Fetch a larger pool for better ranking variety
            val relatedEmotions = if (!emotion.isNullOrEmpty() && emotion != "All") {
                com.saurabh.artifact.util.EmotionCategoryMapper.getRelatedEmotions(emotion)
            } else null

            var query = firestore.collection("artifacts")
                .whereEqualTo("isPublic", true)
                .whereEqualTo("status", ArtifactStatus.ACTIVE.name)

            if (relatedEmotions != null) {
                query = query.whereIn("emotion", relatedEmotions)
            }

            query = query.orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(poolSize.toLong())

            if (lastVisible != null) {
                query = query.startAfter(lastVisible)
            }

            val snapshot = query.get().await()
            val suppressedIds = userId?.let { visibilityFilter.getSuppressedIdsSnapshot(it) } ?: emptySet()
            val ignoredUserIds = visibilityFilter.getIgnoredUserIdsSnapshot()

            val rawArtifacts = snapshot.documents.mapNotNull { doc ->
                val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                if ((artifact == null) || artifact.audioUrl.isEmpty()) return@mapNotNull null

                // 2. Safety Invariant Check
                val reportCount = doc.getLong("reportCount") ?: 0L
                val safetyConcernCount = doc.getLong("safetyConcernCount") ?: 0L

                val artifactSnapshot = artifact.copy(
                    reportCount = reportCount,
                    safetyConcernCount = safetyConcernCount,
                    reporterIds = emptyList() // Deprecated
                )

                val isEligible = safetyPolicy.isEligibleForDiscovery(
                    artifact = artifactSnapshot,
                    currentUserId = userId,
                    isSuppressedByUser = suppressedIds.contains(doc.id),
                    ignoredUserIds = ignoredUserIds
                )

                if (isEligible) {
                    artifactSnapshot.slimForFeed()
                } else {
                    null
                }
            }

            // Apply Recommendation Pipeline
            val rankedArtifacts = recommendationService.rank(rawArtifacts, userId)
            
            // Take the requested limit
            val finalArtifacts = rankedArtifacts.take(limit)
            
            Result.success(PaginatedArtifacts(finalArtifacts, snapshot.documents.lastOrNull()))
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FEED, "FEED_DISCOVERY_FETCH_FAILED", throwable = e)
            Result.failure(AppError.from(e))
        }
    }

    /**
     * Fetches the user's emotional profile for ranking.
     */
    suspend fun getEmotionalProfile(userId: String): Result<EmotionalCompatibilityProfile> = withContext(Dispatchers.IO) {
        return@withContext try {
            val doc = firestore.collection("recommendation_profiles")
                .document(userId)
                .get()
                .await()
            
            val profile = doc.toObject(EmotionalCompatibilityProfile::class.java)
            if (profile != null) {
                Result.success(profile)
            } else {
                Result.failure(AppError.NotFound("EmotionalProfile", userId))
            }
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.FEED, "FEED_PROFILE_FETCH_FAILED", throwable = e)
            Result.failure(AppError.from(e))
        }
    }
}
