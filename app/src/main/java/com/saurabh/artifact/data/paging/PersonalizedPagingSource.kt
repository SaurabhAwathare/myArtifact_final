package com.saurabh.artifact.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.FeedRepository
import com.saurabh.artifact.repository.PaginatedArtifacts
import com.saurabh.artifact.service.FeedRanker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PersonalizedPagingSource(
    private val userId: String,
    private val feedRepository: FeedRepository,
    private val feedRanker: FeedRanker,
    private val emotion: String? = null
) : PagingSource<PersonalizedPagingSource.PageKey, Pair<Artifact, Int>>() {

    data class PageKey(
        val resonatedLast: DocumentSnapshot? = null,
        val discoveryLast: DocumentSnapshot? = null,
        val isFirstPage: Boolean = false,
        val offset: Int = 0
    )

    override fun getRefreshKey(state: PagingState<PageKey, Pair<Artifact, Int>>): PageKey? {
        return null // Always refresh from start
    }

    override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, Pair<Artifact, Int>> {
        return withContext(Dispatchers.IO) {
            try {
                val key = params.key ?: PageKey(isFirstPage = true)
                val pageSize = params.loadSize / 2 // Split between two sources

                val resonatedResult = feedRepository.getResonatingArtifacts(
                    userId = userId,
                    limit = pageSize,
                    lastVisible = key.resonatedLast
                ).getOrDefault(PaginatedArtifacts(emptyList(), null))

                val discoveryResult = feedRepository.getDiscoveryCandidates(
                    userId = userId,
                    limit = pageSize,
                    lastVisible = key.discoveryLast
                ).getOrDefault(PaginatedArtifacts(emptyList(), null))

                val combined = (resonatedResult.artifacts + discoveryResult.artifacts)
                    .distinctBy { it.id }

                val ranked = if (combined.isNotEmpty()) {
                    feedRanker.rank(combined, user = null, currentMood = emotion)
                } else {
                    emptyList()
                }

                val nextKey = if (resonatedResult.artifacts.isEmpty() && discoveryResult.artifacts.isEmpty()) {
                    null
                } else {
                    PageKey(
                        resonatedLast = resonatedResult.lastVisible ?: key.resonatedLast,
                        discoveryLast = discoveryResult.lastVisible ?: key.discoveryLast,
                        isFirstPage = false,
                        offset = key.offset + ranked.size
                    )
                }

                LoadResult.Page(
                    data = ranked.mapIndexed { i, artifact -> artifact to (key.offset + i) },
                    prevKey = null,
                    nextKey = nextKey
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }
}
