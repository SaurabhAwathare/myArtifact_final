package com.saurabh.artifact.domain.review.comments

import com.saurabh.artifact.model.ArtifactComment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated component for merging, deduplicating, and sorting comment streams.
 * Decouples retrieval logic from presentation/sorting logic.
 */
@Singleton
class CommentMerger @Inject constructor() {

    /**
     * Merges two lists of comments, removes duplicates, and sorts by creation time.
     * 
     * @param own The user's own reflections (Sanctuary or Resonance).
     * @param shared Approved reflections from others (Resonance only).
     * @return A consolidated list sorted by createdAt DESC.
     */
    fun merge(
        own: List<ArtifactComment>,
        shared: List<ArtifactComment>
    ): List<ArtifactComment> {
        return (own + shared)
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
    }
}
