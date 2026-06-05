package com.saurabh.artifact.domain.feed

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.saurabh.artifact.data.paging.PersonalizedPagingSource
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.FeedRepository
import com.saurabh.artifact.service.FeedRanker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GetPersonalizedFeedFlowUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val feedRepository: FeedRepository,
    private val feedRanker: FeedRanker
) {
    operator fun invoke(): Flow<PagingData<Artifact>> {
        val userId = authRepository.currentUser.value?.uid ?: return flowOf(PagingData.empty())
        
        return Pager(
            config = PagingConfig(
                pageSize = 10,
                prefetchDistance = 2,
                initialLoadSize = 10,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { 
                PersonalizedPagingSource(
                    userId = userId,
                    feedRepository = feedRepository,
                    feedRanker = feedRanker
                ) 
            }
        ).flow.map { pagingData ->
            pagingData.map { artifact ->
                resolveIdentity(artifact, userId)
            }
        }
    }

    private fun resolveIdentity(artifact: Artifact, userId: String): Artifact {
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
