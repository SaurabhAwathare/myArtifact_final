package com.saurabh.artifact.domain.feed

import androidx.paging.PagingData
import androidx.paging.map
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.FeedDisplayItem
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GetFeedFlowUseCase @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val authRepository: AuthRepository
) {
    operator fun invoke(emotion: String?): Flow<PagingData<FeedDisplayItem.ArtifactItem>> {
        return artifactRepository.getArtifactsPager(emotion).map { pagingData ->
            pagingData.map { (artifact, index) ->
                FeedDisplayItem.ArtifactItem(
                    artifact = resolveIdentity(artifact),
                    absoluteIndex = index
                )
            }
        }
    }

    private fun resolveIdentity(artifact: Artifact): Artifact {
        val currentUser = authRepository.currentUser.value ?: return artifact
        val userId = currentUser.uid
        
        return if (artifact.userId == userId) {
            artifact.copy(
                author = artifact.author.copy(
                    name = artifact.author.name.ifEmpty { "anonymous soul" }
                )
            )
        } else {
            artifact
        }
    }
}
