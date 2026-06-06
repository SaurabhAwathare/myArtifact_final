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
import com.saurabh.artifact.model.Artifact
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalPagingApi::class)
class ArtifactRemoteMediator(
    private val firestore: FirebaseFirestore,
    private val database: AppDatabase,
    private val currentUserId: String,
    private val emotion: String? = null
) : RemoteMediator<Int, ArtifactEntity>() {

    private val artifactDao = database.artifactDao()

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, ArtifactEntity>
    ): MediatorResult {
        return try {
            val lastItem = when (loadType) {
                LoadType.REFRESH -> null
                LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
                LoadType.APPEND -> {
                    state.lastItemOrNull() ?: return MediatorResult.Success(endOfPaginationReached = false)
                }
            }

            var query = firestore.collection("artifacts")
                .whereEqualTo("isPublic", true)
                .whereEqualTo("status", com.saurabh.artifact.model.ArtifactStatus.ACTIVE.name)
                .orderBy("createdAt", Query.Direction.DESCENDING)

            if (!emotion.isNullOrEmpty() && emotion != "All") {
                val relatedEmotions = getRelatedEmotions(emotion)
                query = query.whereIn("emotion", relatedEmotions)
            }

            if (lastItem != null) {
                val lastTimestamp = com.google.firebase.Timestamp(lastItem.createdAt / 1000, ((lastItem.createdAt % 1000) * 1_000_000).toInt())
                query = query.startAfter(lastTimestamp)
            }

            val snapshot = query.limit(state.config.pageSize.toLong()).get().await()
            val artifacts = snapshot.documents.mapNotNull { doc ->
                val artifact = doc.toObject(Artifact::class.java)?.copy(id = doc.id)
                if (artifact == null) return@mapNotNull null

                // Moderation Filter: Hide if HIDDEN or if reports are high or if current user reported it
                val reportCount = doc.getLong("reportCount") ?: 0L
                val reporterIds = doc.get("reporterIds") as? List<*> ?: emptyList<String>()
                val modStatus = artifact.moderation.status
                
                val isModerated = modStatus == com.saurabh.artifact.model.ModerationStatus.HIDDEN || 
                                 reportCount >= 3L || 
                                 reporterIds.contains(currentUserId)

                // Filter out artifacts without audio URLs or that aren't active or are moderated
                if (artifact.audioUrl.isNotEmpty() && !isModerated) {
                    mapToEntity(artifact.copy(reportCount = reportCount, reporterIds = reporterIds.map { it.toString() }))
                } else {
                    null
                }
            }

            val endOfPaginationReached = artifacts.isEmpty()

            database.withTransaction {
                // HARDENING: Only clear local data if we actually got a successful non-empty refresh from network
                if (loadType == LoadType.REFRESH && artifacts.isNotEmpty()) {
                    artifactDao.clearAll()
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
            authorAvatarSeed = artifact.author.avatarSeed,
            authorAvatarColor = artifact.author.avatarColor,
            authorAvatarConfigJson = kotlinx.serialization.json.Json.encodeToString(artifact.author.avatarConfig),
            audioUrl = artifact.audioUrl,
            createdAt = artifact.createdAt.toDate().time,
            durationMs = artifact.durationMs,
            title = artifact.title,
            description = artifact.description,
            emotion = artifact.emotion,
            emotionTag = artifact.emotionTag,
            playCount = artifact.playCount,
            reactionCount = artifact.reactionCount,
            commentCount = artifact.commentCount,
            reportCount = artifact.reportCount,
            reporterIds = artifact.reporterIds,
            amplitudeData = artifact.amplitudeData,
            transcriptUrl = artifact.transcriptUrl,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun getRelatedEmotions(emotion: String): List<String> {
        return when (emotion) {
            "Sad" -> listOf("Sad", "Lonely")
            "Lonely" -> listOf("Lonely", "Sad")
            "Anxious" -> listOf("Anxious", "Angry")
            "Happy" -> listOf("Happy", "Motivated")
            "Motivated" -> listOf("Motivated", "Happy")
            else -> listOf(emotion)
        }
    }
}
