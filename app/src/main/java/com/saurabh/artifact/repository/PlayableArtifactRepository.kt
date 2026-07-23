package com.saurabh.artifact.repository

import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
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
    private val artifactRepository: ArtifactRepository,
    private val diagnosticLogger: DiagnosticLogger
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
                        sigilSeed = "", // Will use current user's default or draft metadata
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
}
