package com.saurabh.artifact.data.mapper

import com.google.firebase.Timestamp
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.Visibility
import java.util.Date
import javax.inject.Inject

/**
 * Centralized mapper to convert [ArtifactDraftEntity] into a domain [Artifact].
 * This ensures consistency between different parts of the app that need to display or play drafts.
 */
class DraftToArtifactMapper @Inject constructor() {

    /**
     * Maps a local draft entity to a displayable Artifact.
     *
     * @param draft The local draft entity.
     * @param author The author snapshot to use for the artifact.
     * @param fallbackTitle The title to use if the draft doesn't have one.
     */
    fun map(
        draft: ArtifactDraftEntity,
        author: AuthorSnapshot,
        fallbackTitle: String
    ): Artifact {
        return Artifact(
            id = draft.id,
            userId = author.anonymousId,
            author = author,
            audioUrl = normalizeAudioUrl(draft.localAudioPath),
            createdAt = Timestamp(Date(draft.createdAt)),
            title = draft.title ?: fallbackTitle,
            durationMs = draft.durationMs,
            status = ArtifactStatus.DRAFT,
            amplitudeData = draft.amplitudeData,
            isDraftField = true,
            visibility = Visibility.PRIVATE // Drafts are private until published
        )
    }

    /**
     * Ensures the audio URL is a valid local file URI.
     */
    private fun normalizeAudioUrl(path: String): String {
        return if (path.startsWith("file://")) {
            path
        } else {
            "file://$path"
        }
    }
}
