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

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.artifactDao()
        reportedDao = db.reportedArtifactDao()
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
