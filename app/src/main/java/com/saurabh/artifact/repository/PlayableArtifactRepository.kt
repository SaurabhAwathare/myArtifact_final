package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.PlayableArtifact
import com.saurabh.artifact.model.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayableArtifactRepository @Inject constructor(
    private val draftDao: DraftDao,
    private val artifactRepository: ArtifactRepository
) {
    /**
     * Resolves an artifact ID into a PlayableArtifact by checking local drafts first,
     * then the published artifacts repository (Firestore/Cache).
     */
    suspend fun resolveArtifact(id: String, source: PlaybackSource): Result<PlayableArtifact> = withContext(Dispatchers.IO) {
        try {
            // 1. Check Local Drafts first (Authoritative for review flow)
            val draft = draftDao.getDraftById(id)
            if (draft != null) {
                return@withContext Result.success(
                    PlayableArtifact(
                        id = draft.id,
                        title = draft.title ?: "Untitled Artifact",
                        audioUrl = if (draft.localAudioPath.startsWith("http")) draft.localAudioPath else "file://${draft.localAudioPath}",
                        authorName = "You", // Drafts are always by the current user
                        authorSigil = "", // Sigils are generated during publish
                        avatarSeed = "", // Will use current user's default or draft metadata
                        durationMs = draft.durationMs,
                        sourceType = source,
                        emotion = draft.emotion?.label ?: "",
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
                            avatarSeed = artifact.author.avatarSeed,
                            durationMs = artifact.durationMs,
                            sourceType = source,
                            emotion = artifact.emotion,
                            originalArtifact = artifact
                        )
                    )
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Result.failure(AppError.from(e))
        }
    }
}
