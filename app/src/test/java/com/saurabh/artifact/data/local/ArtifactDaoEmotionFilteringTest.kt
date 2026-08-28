package com.saurabh.artifact.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class ArtifactDaoEmotionFilteringTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ArtifactDao
    private lateinit var reportedDao: ReportedArtifactDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.artifactDao()
        reportedDao = database.reportedArtifactDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `should filter artifacts by single emotion`() = runBlocking {
        val artifacts = listOf(
            createArtifact(id = "H1", emotion = Emotion.HAPPY),
            createArtifact(id = "S1", emotion = Emotion.SAD),
            createArtifact(id = "H2", emotion = Emotion.HAPPY)
        )
        dao.insertAll(artifacts)

        val pagingSource = dao.getArtifactsPagedFiltered("", listOf(Emotion.HAPPY))
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false
            )
        ) as PagingSource.LoadResult.Page

        assertEquals(2, result.data.size)
        assertTrue(result.data.all { it.emotion == Emotion.HAPPY })
        assertTrue(result.data.any { it.id == "H1" })
        assertTrue(result.data.any { it.id == "H2" })
    }

    @Test
    fun `should filter artifacts by multiple emotions`() = runBlocking {
        val artifacts = listOf(
            createArtifact(id = "H1", emotion = Emotion.HAPPY),
            createArtifact(id = "S1", emotion = Emotion.SAD),
            createArtifact(id = "M1", emotion = Emotion.MOTIVATED),
            createArtifact(id = "A1", emotion = Emotion.ANGRY)
        )
        dao.insertAll(artifacts)

        val filter = listOf(Emotion.HAPPY, Emotion.MOTIVATED)
        val pagingSource = dao.getArtifactsPagedFiltered("", filter)
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false
            )
        ) as PagingSource.LoadResult.Page

        assertEquals(2, result.data.size)
        assertTrue(result.data.any { it.id == "H1" })
        assertTrue(result.data.any { it.id == "M1" })
    }

    @Test
    fun `should return all artifacts when no filter is applied`() = runBlocking {
        val artifacts = listOf(
            createArtifact(id = "H1", emotion = Emotion.HAPPY),
            createArtifact(id = "S1", emotion = Emotion.SAD)
        )
        dao.insertAll(artifacts)

        val pagingSource = dao.getArtifactsPaged("")
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 10,
                placeholdersEnabled = false
            )
        ) as PagingSource.LoadResult.Page

        assertEquals(2, result.data.size)
    }

    @Test
    fun `getArtifactsPagedFiltered should exclude SUPPRESSED artifacts`() = runBlocking {
        val active = createArtifact(id = "active", emotion = Emotion.HAPPY, state = RecommendationState.ACTIVE)
        val suppressed = createArtifact(id = "suppressed", emotion = Emotion.HAPPY, state = RecommendationState.SUPPRESSED)
        
        dao.insertAll(listOf(active, suppressed))
        
        val pagingSource = dao.getArtifactsPagedFiltered("user1", listOf(Emotion.HAPPY))
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page
        
        val ids = result.data.map { it.id }
        assertTrue("Active artifact should be visible", ids.contains("active"))
        assertFalse("Suppressed artifact should be hidden", ids.contains("suppressed"))
    }

    @Test
    fun `getArtifactsPagedFiltered should exclude locally reported artifacts`() = runBlocking {
        val artifact = createArtifact(id = "reported", emotion = Emotion.HAPPY)
        dao.insertAll(listOf(artifact))
        
        reportedDao.insert(ReportedArtifactEntity("user1", "reported", System.currentTimeMillis()))
        
        val pagingSource = dao.getArtifactsPagedFiltered("user1", listOf(Emotion.HAPPY))
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page
        
        val ids = result.data.map { it.id }
        assertFalse("Reported artifact should be hidden", ids.contains("reported"))
    }

    @Test
    fun `should delete artifacts by emotion list`() = runBlocking {
        val artifacts = listOf(
            createArtifact(id = "H1", emotion = Emotion.HAPPY),
            createArtifact(id = "H2", emotion = Emotion.HAPPY),
            createArtifact(id = "S1", emotion = Emotion.SAD)
        )
        dao.insertAll(artifacts)

        dao.deleteArtifactsByEmotions(listOf(Emotion.HAPPY))

        val remaining = dao.getArtifactsPaged("").load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page

        assertEquals(1, remaining.data.size)
        assertEquals("S1", remaining.data[0].id)
    }

    private fun createArtifact(
        id: String, 
        emotion: Emotion, 
        state: RecommendationState = RecommendationState.ACTIVE
    ): ArtifactEntity {
        return ArtifactEntity(
            id = id,
            userId = "user",
            authorAnonymousId = "anon",
            authorName = "Author",
            authorSigil = "S",
            authorSigilSeed = "seed",
            authorSigilColor = "color",
            authorSigilConfigJson = "{}",
            audioUrl = "url",
            createdAt = System.currentTimeMillis(),
            durationMs = 1000,
            title = "Title",
            description = "Desc",
            emotion = emotion,
            emotionTag = emotion.label,
            playCount = 0,
            reactionCount = 0,
            amplitudeData = emptyList(),
            recommendationState = state
        )
    }
}
