package com.saurabh.artifact.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.saurabh.artifact.model.Emotion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtifactDaoEmotionFilteringTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ArtifactDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.artifactDao()
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

        val pagingSource = dao.getArtifactsPaged("", listOf(Emotion.HAPPY))
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
        val pagingSource = dao.getArtifactsPaged("", filter)
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
    fun `should return all artifacts when emotion filter is null`() = runBlocking {
        val artifacts = listOf(
            createArtifact(id = "H1", emotion = Emotion.HAPPY),
            createArtifact(id = "S1", emotion = Emotion.SAD)
        )
        dao.insertAll(artifacts)

        val pagingSource = dao.getArtifactsPaged("", null)
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
    fun `should delete artifacts by emotion category`() = runBlocking {
        val artifacts = listOf(
            createArtifact(id = "H1", emotion = Emotion.HAPPY),
            createArtifact(id = "H2", emotion = Emotion.HAPPY),
            createArtifact(id = "S1", emotion = Emotion.SAD)
        )
        dao.insertAll(artifacts)

        dao.deleteArtifactsByEmotions(listOf(Emotion.HAPPY))

        val remaining = dao.getArtifactsPaged("", null).load(
            PagingSource.LoadParams.Refresh(null, 10, false)
        ) as PagingSource.LoadResult.Page

        assertEquals(1, remaining.data.size)
        assertEquals("S1", remaining.data[0].id)
    }

    private fun createArtifact(id: String, emotion: Emotion): ArtifactEntity {
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
            amplitudeData = emptyList()
        )
    }
}
