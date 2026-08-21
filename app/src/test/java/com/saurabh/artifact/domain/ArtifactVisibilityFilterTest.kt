package com.saurabh.artifact.domain

import com.saurabh.artifact.data.local.ReportedArtifactDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ArtifactVisibilityFilterTest {

    private val reportedArtifactDao = mockk<ReportedArtifactDao>()
    private lateinit var filter: ArtifactVisibilityFilter

    @Before
    fun setup() {
        filter = ArtifactVisibilityFilter(reportedArtifactDao)
    }

    @Test
    fun `getSuppressedIdsSnapshot should return set of IDs from DAO`() = runBlocking {
        val userId = "user1"
        val reportedIds = listOf("art1", "art2")
        coEvery { reportedArtifactDao.getReportedArtifactIds(userId) } returns reportedIds

        val result = filter.getSuppressedIdsSnapshot(userId)

        assertEquals(setOf("art1", "art2"), result)
    }

    @Test
    fun `observeSuppressedIds should emit set of IDs from DAO flow`() = runBlocking {
        val userId = "user1"
        val reportedIds = listOf("art1", "art2")
        every { reportedArtifactDao.observeReportedArtifactIds(userId) } returns flowOf(reportedIds)

        val result = filter.observeSuppressedIds(userId).first()

        assertEquals(setOf("art1", "art2"), result)
    }
}
