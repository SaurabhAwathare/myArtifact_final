package com.saurabh.artifact.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.saurabh.artifact.model.Emotion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtifactDaoIndexedQueryTest {

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
    fun `should calculate unique indices for identical timestamps`() = runBlocking {
        val timestamp = 1000L
        val artifacts = listOf(
            createArtifact(id = "A", createdAt = timestamp),
            createArtifact(id = "B", createdAt = timestamp),
            createArtifact(id = "C", createdAt = timestamp)
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

        // Should be sorted by id DESC for identical timestamps
        // 1. C (index 0)
        // 2. B (index 1)
        // 3. A (index 2)
        assertEquals(3, result.data.size)
        
        assertEquals("C", result.data[0].entity.id)
        assertEquals(0, result.data[0].absoluteIndex)
        
        assertEquals("B", result.data[1].entity.id)
        assertEquals(1, result.data[1].absoluteIndex)
        
        assertEquals("A", result.data[2].entity.id)
        assertEquals(2, result.data[2].absoluteIndex)
    }

    @Test
    fun `benchmark large dataset query performance`() = runBlocking {
        val count = 1000
        val artifacts = (0 until count).map { i ->
            createArtifact(id = "id_$i", createdAt = i.toLong())
        }
        dao.insertAll(artifacts)

        val startTime = System.currentTimeMillis()
        val pagingSource = dao.getArtifactsPaged("")
        val result = pagingSource.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        ) as PagingSource.LoadResult.Page
        val duration = System.currentTimeMillis() - startTime

        assertEquals(20, result.data.size)
        // Note: With 1000 items, O(N^2) might still be fast in SQLite. 
        // 1000 * 1000 = 1,000,000 comparisons.
        println("Query duration for 20 items in 1000 dataset: ${duration}ms")
        assertTrue("Query took too long: ${duration}ms", duration < 500) // generous 500ms limit
    }

    private fun createArtifact(id: String, createdAt: Long): ArtifactEntity {
        return ArtifactEntity(
            id = id,
            userId = "user",
            authorAnonymousId = "anon",
            authorName = "Author",
            authorSigil = "S",
            authorAvatarSeed = "seed",
            authorAvatarColor = "color",
            authorAvatarConfigJson = "{}",
            audioUrl = "url",
            createdAt = createdAt,
            durationMs = 1000,
            title = "Title",
            description = "Desc",
            emotion = Emotion.NEUTRAL,
            emotionTag = "Neutral",
            playCount = 0,
            reactionCount = 0,
            commentCount = 0,
            amplitudeData = emptyList()
        )
    }
    
    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }
}
