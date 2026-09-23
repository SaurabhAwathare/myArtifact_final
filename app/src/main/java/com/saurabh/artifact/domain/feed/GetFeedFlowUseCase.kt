package com.saurabh.artifact.domain.feed

import androidx.paging.PagingData
import androidx.paging.map
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.FeedDisplayItem
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.ResolvedCreatorIdentity
import com.saurabh.artifact.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GetFeedFlowUseCase @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository? = null
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

    private suspend fun resolveIdentity(artifact: Artifact): Artifact {
        val anonId = artifact.author.anonymousId
        if (anonId.isBlank() && artifact.userId.isBlank()) return artifact

        val creatorProfile = userRepository?.getCreatorProfile(artifact.userId, anonId)
        val resolved = ResolvedCreatorIdentity.resolve(artifact, creatorProfile)

        return artifact.copy(
            author = AuthorSnapshot(
                anonymousId = resolved.personaId,
                name = resolved.name,
                sigil = resolved.sigil,
                sigilSeed = resolved.sigilSeed,
                sigilColor = resolved.sigilColor,
                sigilConfig = resolved.sigilConfig
            )
        )
    }
}
