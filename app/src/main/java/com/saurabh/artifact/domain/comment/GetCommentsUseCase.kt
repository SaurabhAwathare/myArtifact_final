package com.saurabh.artifact.domain.comment

import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.repository.CommentRepository
import com.saurabh.artifact.repository.PaginatedComments
import javax.inject.Inject

/**
 * Use case for retrieving a paginated list of comments for an artifact.
 */
class GetCommentsUseCase @Inject constructor(
    private val repository: CommentRepository
) {
    /**
     * Fetches a page of comments.
     * 
     * @param artifactId The ID of the artifact.
     * @param limit The maximum number of comments to fetch.
     * @param lastVisible The cursor for pagination.
     * @return A [Result] containing [PaginatedComments].
     */
    suspend operator fun invoke(
        artifactId: String,
        limit: Int = 20,
        lastVisible: DocumentSnapshot? = null
    ): Result<PaginatedComments> {
        return repository.getComments(artifactId, limit, lastVisible)
    }
}
