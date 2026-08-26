package com.saurabh.artifact.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.saurabh.artifact.data.local.AppDatabase
import com.saurabh.artifact.data.local.ArtifactEntity
import com.saurabh.artifact.data.local.ArtifactEntityWithIndex
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.util.NetworkUtils
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalPagingApi::class)
class ArtifactRemoteMediator(
    private val firestore: FirebaseFirestore,
    private val database: AppDatabase,
    private val currentUserId: String,
    private val emotion: String? = null,
    private val safetyPolicy: com.saurabh.artifact.domain.SafetyPolicy
) : RemoteMediator<Int, ArtifactEntityWithIndex>() {

    private val artifactDao = database.artifactDao()
    private val reportedArtifactDao = database.reportedArtifactDao()

    override suspend fun initialize(): InitializeAction {
        // Targeted Fix: If an emotion category is selected, ALWAYS force a refresh
        // to ensure we get fresh content for that category regardless of existing cache.
        return if (!emotion.isNullOrEmpty() && emotion != "All") {
            InitializeAction.LAUNCH_INITIAL_REFRESH
        } else if (hasUsableCachedData()) {
            InitializeAction.SKIP_INITIAL_REFRESH
        } else {
            InitializeAction.LAUNCH_INITIAL_REFRESH
        }
    }

    private suspend fun hasUsableCachedData(): Boolean {
        // Implementation Note: In Phase 2, "usable cached data" is defined as the presence 
        // of at least one locally cached artifact. More advanced freshness validation, 
        // if required in the future, will be addressed separately.
        return artifactDao.hasCachedArtifacts()
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, ArtifactEntityWithIndex>
    ): MediatorResult {
        return try {
            val lastItem = when (loadType) {
                LoadType.REFRESH -> null
                LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
                LoadType.APPEND -> {
                    state.lastItemOrNull()?.entity ?: return MediatorResult.Success(endOfPaginationReached = false)
                }
            }

            val relatedEmotions = if (!emotion.isNullOrEmpty() && emotion != "All") {
                com.saurabh.artifact.util.EmotionCategoryMapper.getRelatedEmotions(emotion)
            } else null

            var query = firestore.collection("artifacts")
                .whereEqualTo("isPublic", true)
                .whereEqualTo("status", com.saurabh.artifact.model.ArtifactStatus.ACTIVE.name)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(com.google.firebase.firestore.FieldPath.documentId(), Query.Direction.DESCENDING)

            if (relatedEmotions != null) {
                query = query.whereIn("emotion", relatedEmotions)
            }

            if (lastItem != null) {
                val lastTimestamp = com.google.firebase.Timestamp(lastItem.createdAt / 1000, ((lastItem.createdAt % 1000) * 1_000_000).toInt())
                query = query.startAfter(lastTimestamp, lastItem.id)
            }

            val snapshot = NetworkUtils.retryWithBackoff {
                query.limit(state.config.pageSize.toLong()).get().await()
            }

            // Fetch local suppression snapshot for the current user
            val suppressedIds = reportedArtifactDao.getReportedArtifactIds(currentUserId).toSet()

            val artifacts = snapshot.documents.mapNotNull { doc ->
                val artifact =
                    doc.toObject(Artifact::class.java)?.copy(id = doc.id) ?: return@mapNotNull null

                // 2. Safety Invariant Check
                // Authoritative Filter: Hide if global moderation thresholds met or if current user reported it
                val reportCount = doc.getLong("reportCount") ?: 0L
                val safetyConcernCount = doc.getLong("safetyConcernCount") ?: 0L
                
                // Note: reporterIds is deprecated and intentionally NOT populated by backend.
                // isSuppressedByUser is now derived from the local Room database (synced from private Firestore markers).
                val isSuppressedByUser = suppressedIds.contains(doc.id)
                
                // Reconstruct transient artifact metadata for policy evaluation
                val artifactSnapshot = artifact.copy(
                    reportCount = reportCount,
                    safetyConcernCount = safetyConcernCount,
                    reporterIds = emptyList() // No longer using public reporterIds
                )

                val isEligible = safetyPolicy.isEligibleForDiscovery(
                    artifact = artifactSnapshot,
                    currentUserId = currentUserId,
                    isSuppressedByUser = isSuppressedByUser
                )

                if (isEligible) {
                    mapToEntity(artifactSnapshot)
                } else {
                    null
                }
            }

            val endOfPaginationReached = artifacts.isEmpty()

            database.withTransaction {
                // Targeted Fix: Only clear data relevant to the current emotion category during refresh.
                if (loadType == LoadType.REFRESH) {
                    if (relatedEmotions != null) {
                        val relatedEmotionEnums = relatedEmotions.mapNotNull { label ->
                            Emotion.entries.find { it.label.equals(label, ignoreCase = true) }
                        }
                        artifactDao.deleteArtifactsByEmotions(relatedEmotionEnums)
                    } else {
                        artifactDao.clearAll()
                    }
                }
                
                if (artifacts.isNotEmpty()) {
                    artifactDao.insertAll(artifacts)
                }
            }

            MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
        } catch (e: Exception) {
            MediatorResult.Error(e)
        }
    }

    private fun mapToEntity(artifact: Artifact): ArtifactEntity {
        return ArtifactEntity(
            id = artifact.id,
            userId = artifact.userId,
            authorAnonymousId = artifact.author.anonymousId,
            authorName = artifact.author.name,
            authorSigil = artifact.author.sigil,
            authorSigilSeed = artifact.author.sigilSeed,
            authorSigilColor = artifact.author.sigilColor,
            authorSigilConfigJson = kotlinx.serialization.json.Json.encodeToString(artifact.author.sigilConfig),
            audioUrl = artifact.audioUrl,
            createdAt = artifact.createdAt.toDate().time,
            durationMs = artifact.durationMs,
            title = artifact.title,
            description = artifact.description,
            emotion = Emotion.entries.find { 
                it.name.equals(artifact.emotion, ignoreCase = true) || 
                it.label.equals(artifact.emotion, ignoreCase = true) 
            } ?: Emotion.NEUTRAL,
            emotionTag = artifact.emotionTag,
            playCount = artifact.playCount,
            reactionCount = artifact.reactionCount,
            reportCount = artifact.reportCount,
            safetyConcernCount = artifact.safetyConcernCount,
            reporterIds = artifact.reporterIds,
            amplitudeData = artifact.amplitudeData,
            transcriptUrl = artifact.transcriptUrl,
            status = artifact.status,
            isDraft = artifact.isDraft,
            lastUpdated = System.currentTimeMillis()
        )
    }

}
