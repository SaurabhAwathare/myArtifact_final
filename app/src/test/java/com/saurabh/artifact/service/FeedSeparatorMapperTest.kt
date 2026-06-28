package com.saurabh.artifact.service

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.FeedDisplayItem
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedSeparatorMapperTest {

    private val mapper = FeedSeparatorMapper()

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
    }

    @Test
    fun `should insert break after every 5 artifacts`() = runTest {
        val artifacts = (0 until 12).map { i ->
            FeedDisplayItem.ArtifactItem(
                artifact = Artifact(id = "id_$i"),
                absoluteIndex = i
            )
        }
        val pagingData = PagingData.from(artifacts)

        val result = flowOf(mapper.mapToDisplayItems(pagingData)).asSnapshot()

        // 12 artifacts + 2 breaks (after index 4 and 9) = 14 items
        assertEquals(14, result.size)
        
        // Check first break
        assertTrue(result[4] is FeedDisplayItem.ArtifactItem)
        assertEquals("id_4", (result[4] as FeedDisplayItem.ArtifactItem).id)
        assertTrue(result[5] is FeedDisplayItem.BreakItem)
        assertEquals("break_after_id_4", (result[5] as FeedDisplayItem.BreakItem).id)
        
        // Check second break
        // artifacts 0-4 (5), break (1), artifacts 5-9 (5), break (1), artifacts 10-11 (2)
        // items: 0 1 2 3 4 [B] 5 6 7 8 9 [B] 10 11
        // Indices: 0 1 2 3 4 5 6 7 8 9 10 11 12 13
        assertTrue(result[10] is FeedDisplayItem.ArtifactItem)
        assertEquals("id_9", (result[10] as FeedDisplayItem.ArtifactItem).id)
        assertTrue(result[11] is FeedDisplayItem.BreakItem)
        assertEquals("break_after_id_9", (result[11] as FeedDisplayItem.BreakItem).id)
    }

    @Test
    fun `should not insert break if after is null`() = runTest {
        val artifacts = (0 until 5).map { i ->
            FeedDisplayItem.ArtifactItem(
                artifact = Artifact(id = "id_$i"),
                absoluteIndex = i
            )
        }
        val pagingData = PagingData.from(artifacts)

        val result = flowOf(mapper.mapToDisplayItems(pagingData)).asSnapshot()

        // 5 artifacts, index 4 is the last one. 'after' will be null, so no break.
        assertEquals(5, result.size)
        val lastItem = result.last()
        assertTrue(lastItem is FeedDisplayItem.ArtifactItem)
        assertEquals("id_4", (lastItem as FeedDisplayItem.ArtifactItem).id)
    }
}
