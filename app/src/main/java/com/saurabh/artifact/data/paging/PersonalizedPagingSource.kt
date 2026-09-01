package com.saurabh.artifact.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.saurabh.artifact.diagnostics.ArtifactLogger
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.FeedRepository
import com.saurabh.artifact.repository.PaginatedArtifacts
import com.saurabh.artifact.service.FeedRanker
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PersonalizedPagingSource(
    private val userId: String,
    private val feedRepository: FeedRepository,
    private val feedRanker: FeedRanker,
    private val visibilityFilter: ArtifactVisibilityFilter,
    private val emotion: String? = null,
) : PagingSource<PersonalizedPagingSource.PageKey, Pair<Artifact, Int>>() {

    private val emittedIds = mutableSetOf<String>()
    private var suppressedIdsSnapshot: Set<String>? = null
    private var ignoredUserIdsSnapshot: Set<String>? = null

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
                    lastVisible = key.resonatedLast,
                    emotion = emotion
                ).getOrDefault(PaginatedArtifacts(emptyList(), null))

                val discoveryResult = feedRepository.getDiscoveryCandidates(
                    userId = userId,
                    limit = pageSize,
                    lastVisible = key.discoveryLast,
                    emotion = emotion
                ).getOrDefault(PaginatedArtifacts(emptyList(), null))

                ArtifactLogger.d(DiagnosticCategory.FEED, "PAGING_SOURCE_LOAD", mapOf("offset" to key.offset))

                val suppressed = suppressedIdsSnapshot ?: visibilityFilter.getSuppressedIdsSnapshot(userId).also {
                    suppressedIdsSnapshot = it
                }
                val ignored = ignoredUserIdsSnapshot ?: visibilityFilter.getIgnoredUserIdsSnapshot(userId).also {
                    ignoredUserIdsSnapshot = it
                }

                val combined = (resonatedResult.artifacts + discoveryResult.artifacts)
                    .asSequence()
                    .filter { (it.id !in emittedIds) && (it.id !in suppressed) && (it.author.anonymousId !in ignored) }
                    .distinctBy { it.id }
                    .toList()

                emittedIds.addAll(combined.map { it.id })

                ArtifactLogger.d(DiagnosticCategory.FEED, "PAGING_SOURCE_COMBINED", mapOf("count" to combined.size))

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
