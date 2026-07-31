package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.data.mapper.DraftToArtifactMapper
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.PlayableArtifact
import com.saurabh.artifact.model.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayableArtifactRepository @Inject constructor(
    private val draftDao: DraftDao,
    private val artifactRepository: ArtifactRepository,
    private val draftToArtifactMapper: DraftToArtifactMapper,
    private val userRepository: UserRepository,
    private val diagnosticLogger: DiagnosticLogger
) {
    /**
     * Resolves an artifact ID into a PlayableArtifact by checking local drafts first,
     * then the published artifacts repository (Firestore/Cache).
     */
    suspend fun resolveArtifact(id: String, source: PlaybackSource): Result<PlayableArtifact> = withContext(Dispatchers.IO) {
        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            
            // 1. Check Local Drafts first (Authoritative for review flow)
            val draft = draftDao.getDraftById(id, userId)
            if (draft != null) {
                val author = userRepository.getCachedProfile()?.let { AuthorSnapshot.fromUser(it) } 
                    ?: AuthorSnapshot(name = "You")
                
                val artifact = draftToArtifactMapper.map(draft, author, "Untitled Artifact")
                
                return@withContext Result.success(
                    PlayableArtifact(
                        id = artifact.id,
                        title = artifact.title,
                        audioUrl = artifact.audioUrl,
                        authorName = artifact.author.name,
                        authorSigil = artifact.author.sigil,
                        sigilSeed = artifact.author.sigilSeed,
                        durationMs = artifact.durationMs,
                        sourceType = source,
                        emotion = artifact.emotion,
                        originalDraft = draft
                    )
                )
            }

            // 2. Check Published Artifacts
            artifactRepository.getArtifactById(id).fold(
                onSuccess = { artifact ->
                    if (artifact.status == com.saurabh.artifact.model.ArtifactStatus.DELETED) {
                        return@withContext Result.failure(AppError.NotFound("Artifact", id))
                    }

                    Result.success(
                        PlayableArtifact(
                            id = artifact.id,
                            title = artifact.title,
                            audioUrl = artifact.audioUrl,
                            authorName = artifact.author.name,
                            authorSigil = artifact.author.sigil,
                            sigilSeed = artifact.author.sigilSeed,
                            durationMs = artifact.durationMs,
                            sourceType = source,
                            emotion = artifact.emotion,
                            originalArtifact = artifact
                        )
                    )
                },
                onFailure = { error ->
                    diagnosticLogger.error(
                        category = DiagnosticCategory.PLAYER,
                        eventName = "ARTIFACT_RESOLVE_FAILED",
                        metadata = mapOf(
                            LogKeys.ARTIFACT_ID to id,
                            "errorType" to error.javaClass.simpleName,
                            "errorMessage" to (error.message ?: "No message")
                        )
                    )
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            val error = AppError.from(e)
            diagnosticLogger.error(
                category = DiagnosticCategory.PLAYER,
                eventName = "ARTIFACT_RESOLVE_FAILED_WRAPPER",
                metadata = mapOf(
                    LogKeys.ARTIFACT_ID to id,
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "No message")
                )
            )
            Result.failure(error)
        }
    }

    /**
     * Resolves multiple artifact IDs into a list of domain Artifacts.
     * Prioritizes local drafts over remote artifacts for each ID.
     */
    suspend fun resolveArtifactsByIds(ids: List<String>): Result<List<Artifact>> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext Result.success(emptyList())

        try {
            val userId = userRepository.getCurrentUserId() ?: return@withContext Result.failure(AppError.Unauthenticated())
            
            // 1. Fetch all matching drafts in one go (if possible, otherwise one by one for now)
            // Note: DraftDao doesn't have getDraftsByIds yet, so we'll fetch them individually
            // or we could add it to DraftDao. For simplicity and minimum risk, we iterate.
            val draftsMap = ids.mapNotNull { id -> draftDao.getDraftById(id, userId) }.associateBy { it.id }
            
            val author = if (draftsMap.isNotEmpty()) {
                userRepository.getCachedProfile()?.let { AuthorSnapshot.fromUser(it) } 
                    ?: AuthorSnapshot(name = "You")
            } else {
                null
            }

            val missingIds = ids.filter { !draftsMap.containsKey(it) }
            
            val remoteArtifactsResult = if (missingIds.isNotEmpty()) {
                artifactRepository.getArtifactsByIds(missingIds)
            } else {
                Result.success(emptyList())
            }

            val remoteArtifactsMap = remoteArtifactsResult.getOrDefault(emptyList()).associateBy { it.id }

            // 2. Reconstruct the list in the original order
            val resolvedList = ids.mapNotNull { id ->
                draftsMap[id]?.let { draft ->
                    draftToArtifactMapper.map(draft, author ?: AuthorSnapshot(name = "You"), "Untitled Artifact")
                } ?: remoteArtifactsMap[id]
            }

            Result.success(resolvedList)
        } catch (e: Exception) {
            diagnosticLogger.error(DiagnosticCategory.PLAYER, "BATCH_RESOLVE_FAILED", mapOf("count" to ids.size), e)
            Result.failure(e)
        }
    }
}
