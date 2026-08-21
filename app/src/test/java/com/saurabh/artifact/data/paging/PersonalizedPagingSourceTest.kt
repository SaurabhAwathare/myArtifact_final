package com.saurabh.artifact.data.paging

import android.util.Log
import androidx.paging.PagingSource
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.repository.FeedRepository
import com.saurabh.artifact.repository.PaginatedArtifacts
import com.saurabh.artifact.service.FeedRanker
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalizedPagingSourceTest {

    private val feedRepository = mockk<FeedRepository>()
    private val feedRanker = mockk<FeedRanker>()
    private val visibilityFilter = mockk<ArtifactVisibilityFilter>()
    private val userId = "test_user"
    
    private lateinit var pagingSource: PersonalizedPagingSource

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        coEvery { visibilityFilter.getSuppressedIdsSnapshot(any()) } returns emptySet()

        pagingSource = PersonalizedPagingSource(
            userId = userId,
            feedRepository = feedRepository,
            feedRanker = feedRanker,
            visibilityFilter = visibilityFilter
        )
        
        coEvery { feedRanker.rank(any(), any(), any()) } answers { 
            @Suppress("UNCHECKED_CAST")
            it.invocation.args[0] as List<Artifact> 
        }
    }

    @Test
    fun `load filters duplicates across multiple pages`() = runTest {
        val artifact1 = Artifact(id = "1")
        val artifact2 = Artifact(id = "2")
        val artifact3 = Artifact(id = "3")

        val page1 = PaginatedArtifacts(listOf(artifact1), null)
        val page2 = PaginatedArtifacts(listOf(artifact2), null)
        
        coEvery { feedRepository.getResonatingArtifacts(any(), any(), any()) } returns Result.success(page1)
        coEvery { feedRepository.getDiscoveryCandidates(any(), any(), any()) } returns Result.success(page2)

        val params1 = PagingSource.LoadParams.Refresh<PersonalizedPagingSource.PageKey>(
            key = null,
            loadSize = 10,
            placeholdersEnabled = false
        )

        val result1 = pagingSource.load(params1)
        if (result1 is PagingSource.LoadResult.Error) {
            throw result1.throwable
        }
        val pageResult1 = result1 as PagingSource.LoadResult.Page
        assertEquals(listOf("1", "2"), pageResult1.data.map { it.first.id })

        val page3 = PaginatedArtifacts(listOf(artifact2), null)
        val page4 = PaginatedArtifacts(listOf(artifact3), null)
        coEvery { feedRepository.getResonatingArtifacts(any(), any(), any()) } returns Result.success(page3)
        coEvery { feedRepository.getDiscoveryCandidates(any(), any(), any()) } returns Result.success(page4)

        val params2 = PagingSource.LoadParams.Append(
            key = pageResult1.nextKey!!,
            loadSize = 10,
            placeholdersEnabled = false
        )

        val result2 = pagingSource.load(params2)
        if (result2 is PagingSource.LoadResult.Error) {
            throw result2.throwable
        }
        val pageResult2 = result2 as PagingSource.LoadResult.Page
        assertEquals(listOf("3"), pageResult2.data.map { it.first.id })
    }

    @Test
    fun `offset calculation is correct even when items are filtered`() = runTest {
        val artifact1 = Artifact(id = "1")
        val artifact2 = Artifact(id = "2")
        val artifact3 = Artifact(id = "3")

        val page1 = PaginatedArtifacts(listOf(artifact1), null)
        val page2 = PaginatedArtifacts(listOf(artifact2), null)
        coEvery { feedRepository.getResonatingArtifacts(any(), any(), any()) } returns Result.success(page1)
        coEvery { feedRepository.getDiscoveryCandidates(any(), any(), any()) } returns Result.success(page2)

        val result1 = pagingSource.load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page
        
        assertEquals(0, result1.data[0].second)
        assertEquals(1, result1.data[1].second)
        assertEquals(2, result1.nextKey?.offset)

        val page3 = PaginatedArtifacts(listOf(artifact2), null)
        val page4 = PaginatedArtifacts(listOf(artifact3), null)
        coEvery { feedRepository.getResonatingArtifacts(any(), any(), any()) } returns Result.success(page3)
        coEvery { feedRepository.getDiscoveryCandidates(any(), any(), any()) } returns Result.success(page4)

        val result2 = pagingSource.load(
            PagingSource.LoadParams.Append(result1.nextKey!!, 10, false)
        ) as PagingSource.LoadResult.Page

        assertEquals(1, result2.data.size)
        assertEquals("3", result2.data[0].first.id)
        assertEquals(2, result2.data[0].second) // Should be 2nd index (0, 1, [2])
        assertEquals(3, result2.nextKey?.offset)
    }

    @Test
    fun `load handles paging errors gracefully`() = runTest {
        coEvery { feedRepository.getResonatingArtifacts(any(), any(), any()) } returns 
            Result.failure(Exception("Network error"))

        val params = PagingSource.LoadParams.Refresh<PersonalizedPagingSource.PageKey>(
            key = null,
            loadSize = 10,
            placeholdersEnabled = false
        )

        val result = pagingSource.load(params)
        assertTrue(result is PagingSource.LoadResult.Error)
    }

    @Test
    fun `load filters suppressed artifacts using snapshot`() = runTest {
        val artifact1 = Artifact(id = "1")
        val artifact2 = Artifact(id = "2")
        val page1 = PaginatedArtifacts(listOf(artifact1, artifact2), null)
        
        coEvery { feedRepository.getResonatingArtifacts(any(), any(), any()) } returns Result.success(page1)
        coEvery { feedRepository.getDiscoveryCandidates(any(), any(), any()) } returns Result.success(PaginatedArtifacts(emptyList(), null))
        coEvery { visibilityFilter.getSuppressedIdsSnapshot(userId) } returns setOf("1")

        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page

        assertEquals(1, result.data.size)
        assertEquals("2", result.data[0].first.id)
        
        // Verify filter only called once (snapshot behavior)
        coVerify(exactly = 1) { visibilityFilter.getSuppressedIdsSnapshot(userId) }
        
        pagingSource.load(PagingSource.LoadParams.Append(result.nextKey!!, 10, false))
        coVerify(exactly = 1) { visibilityFilter.getSuppressedIdsSnapshot(userId) }
    }
}
