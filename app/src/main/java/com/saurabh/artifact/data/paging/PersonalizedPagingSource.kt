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
    private val feedRanker: FeedRanker
) : PagingSource<PersonalizedPagingSource.PageKey, Artifact>() {

    data class PageKey(
        val resonatedLast: DocumentSnapshot? = null,
        val discoveryLast: DocumentSnapshot? = null,
        val isFirstPage: Boolean = false
    )

    override fun getRefreshKey(state: PagingState<PageKey, Artifact>): PageKey? {
        return null // Always refresh from start
    }

    override suspend fun load(params: LoadParams<PageKey>): LoadResult<PageKey, Artifact> {
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
                    // Note: In a real app, we'd pass the actual user object and mood
                    feedRanker.rank(combined, user = null, currentMood = null)
                } else {
                    emptyList()
                }

                val nextKey = if (resonatedResult.artifacts.isEmpty() && discoveryResult.artifacts.isEmpty()) {
                    null
                } else {
                    PageKey(
                        resonatedLast = resonatedResult.lastVisible ?: key.resonatedLast,
                        discoveryLast = discoveryResult.lastVisible ?: key.discoveryLast,
                        isFirstPage = false
                    )
                }

                LoadResult.Page(
                    data = ranked,
                    prevKey = null,
                    nextKey = nextKey
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }
}
