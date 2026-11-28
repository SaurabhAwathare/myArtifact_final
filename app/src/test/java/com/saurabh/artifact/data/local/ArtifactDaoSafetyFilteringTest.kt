package com.saurabh.artifact.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.saurabh.artifact.model.ArtifactStatus
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.model.RecommendationState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtifactDaoSafetyFilteringTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ArtifactDao
    private lateinit var reportedDao: ReportedArtifactDao
    private lateinit var suppressedDao: SuppressedArtifactDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.artifactDao()
        reportedDao = db.reportedArtifactDao()
        suppressedDao = db.suppressedArtifactDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun `getArtifactsPaged should exclude SUPPRESSED artifacts`() = runBlocking {
        val active = createArtifact("active", RecommendationState.ACTIVE)
        val suppressed = createArtifact("suppressed", RecommendationState.SUPPRESSED)
        
        dao.insertAll(listOf(active, suppressed))
        
        val pagingSource = dao.getArtifactsPaged("user1")
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page
        
        val ids = result.data.map { it.id }
        assertTrue("Active artifact should be visible", ids.contains("active"))
        assertFalse("Suppressed artifact should be hidden", ids.contains("suppressed"))
    }

    @Test
    fun `getArtifactsPaged should exclude locally reported artifacts`() = runBlocking {
        val artifact = createArtifact("reported", RecommendationState.ACTIVE)
        dao.insertAll(listOf(artifact))
        
        reportedDao.insert(ReportedArtifactEntity("user1", "reported", System.currentTimeMillis()))
        
        val pagingSource = dao.getArtifactsPaged("user1")
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page
        
        val ids = result.data.map { it.id }
        assertFalse("Reported artifact should be hidden", ids.contains("reported"))
    }

    @Test
    fun `getArtifactsPaged should exclude NOT_FOR_ME suppressed artifacts`() = runBlocking {
        val artifact1 = createArtifact("art1", RecommendationState.ACTIVE)
        val artifact2 = createArtifact("art2", RecommendationState.ACTIVE)
        dao.insertAll(listOf(artifact1, artifact2))

        suppressedDao.insert(SuppressedArtifactEntity("user1", "art1", System.currentTimeMillis()))

        val pagingSource = dao.getArtifactsPaged("user1")
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page

        val ids = result.data.map { it.id }
        assertFalse("NOT_FOR_ME suppressed artifact1 should be excluded for user1", ids.contains("art1"))
        assertTrue("Unsuppressed artifact2 should remain visible for user1", ids.contains("art2"))

        // Verify isolation: user2 should still see art1
        val pagingSourceUser2 = dao.getArtifactsPaged("user2")
        val resultUser2 = pagingSourceUser2.load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page
        assertTrue("User2 should still see art1", resultUser2.data.map { it.id }.contains("art1"))
    }

    @Test
    fun `getArtifactsPagedFiltered and getRecentCachedArtifacts should exclude NOT_FOR_ME suppressed artifacts`() = runBlocking {
        val artifact1 = createArtifact("art1", RecommendationState.ACTIVE)
        val artifact2 = createArtifact("art2", RecommendationState.ACTIVE)
        dao.insertAll(listOf(artifact1, artifact2))

        suppressedDao.insert(SuppressedArtifactEntity("user1", "art1", System.currentTimeMillis()))

        // getArtifactsPagedFiltered
        val filteredPagingSource = dao.getArtifactsPagedFiltered("user1", listOf(Emotion.HAPPY))
        val filteredResult = filteredPagingSource.load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page
        assertFalse("Filtered paged must exclude art1", filteredResult.data.map { it.id }.contains("art1"))
        assertTrue("Filtered paged must include art2", filteredResult.data.map { it.id }.contains("art2"))

        // getRecentCachedArtifacts
        val recentList = dao.getRecentCachedArtifacts("user1", 10)
        assertFalse("Recent cached list must exclude art1", recentList.map { it.id }.contains("art1"))
        assertTrue("Recent cached list must include art2", recentList.map { it.id }.contains("art2"))

        // getRecentCachedArtifactsFiltered
        val recentFilteredList = dao.getRecentCachedArtifactsFiltered("user1", listOf(Emotion.HAPPY), limit = 10)
        assertFalse("Recent cached filtered list must exclude art1", recentFilteredList.map { it.id }.contains("art1"))
        assertTrue("Recent cached filtered list must include art2", recentFilteredList.map { it.id }.contains("art2"))
    }

    @Test
    fun `insertAll should update recommendationState of existing artifact`() = runBlocking {
        val initial = createArtifact("art1", RecommendationState.ACTIVE)
        dao.insertAll(listOf(initial))
        
        val cached = dao.getArtifactById("art1")
        assertEquals(RecommendationState.ACTIVE, cached?.recommendationState)
        
        val updated = createArtifact("art1", RecommendationState.SUPPRESSED)
        dao.insertAll(listOf(updated))
        
        val cachedAfterUpdate = dao.getArtifactById("art1")
        assertEquals(RecommendationState.SUPPRESSED, cachedAfterUpdate?.recommendationState)
    }

    private fun createArtifact(id: String, recommendationState: RecommendationState): ArtifactEntity {
        return ArtifactEntity(
            id = id,
            userId = "user",
            authorAnonymousId = "anon",
            authorName = "Name",
            authorSigil = "sigil",
            authorSigilSeed = "seed",
            authorSigilColor = "color",
            authorSigilConfigJson = "{}",
            audioUrl = "url",
            createdAt = System.currentTimeMillis(),
            durationMs = 1000,
            title = "Title",
            description = "Desc",
            emotion = Emotion.HAPPY,
            emotionTag = "happy",
            playCount = 0,
            reactionCount = 0,
            amplitudeData = emptyList(),
            status = ArtifactStatus.ACTIVE,
            recommendationState = recommendationState
        )
    }
}
