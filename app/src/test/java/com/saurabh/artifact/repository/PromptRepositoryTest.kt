package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.saurabh.artifact.data.local.PromptDao
import com.saurabh.artifact.data.local.PromptEntity
import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PromptRepositoryTest {
    private val promptDao = mockk<PromptDao>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var repository: PromptRepository

    private val sampleJsonContent = """
        [
            {"id":"L1_001","question":"Q1","category":"GENERAL","tone":"GENTLE","depthLevel":1},
            {"id":"L1_002","question":"Q2","category":"GENERAL","tone":"GENTLE","depthLevel":1},
            {"id":"L2_001","question":"Q2","category":"GENERAL","tone":"GENTLE","depthLevel":2},
            {"id":"valid_id","question":"Valid Question","category":"GENERAL","tone":"GENTLE","depthLevel":1},
            {"id":"P1","question":"Q1","category":"GENERAL","tone":"GENTLE","depthLevel":1}
        ]
    """.trimIndent()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        every { context.assets.open("prompts.json") } answers { sampleJsonContent.byteInputStream() }
        coEvery { promptDao.getPromptCount() } returns 10

        repository = PromptRepository(
            promptDao = { promptDao },
            context = context
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `calculateMaxDepth correct mapping for progression thresholds`() {
        assertEquals(1, repository.calculateMaxDepth(0))
        assertEquals(1, repository.calculateMaxDepth(1))
        assertEquals(1, repository.calculateMaxDepth(2))

        assertEquals(2, repository.calculateMaxDepth(3))
        assertEquals(2, repository.calculateMaxDepth(5))
        assertEquals(2, repository.calculateMaxDepth(9))

        assertEquals(3, repository.calculateMaxDepth(10))
        assertEquals(3, repository.calculateMaxDepth(15))
        assertEquals(3, repository.calculateMaxDepth(24))

        assertEquals(4, repository.calculateMaxDepth(25))
        assertEquals(4, repository.calculateMaxDepth(30))
        assertEquals(4, repository.calculateMaxDepth(100))
    }

    @Test
    fun `getNewPrompt should select prompt based on depth level 1 for progression 0`() = runBlocking {
        coEvery { promptDao.getUnconsumedCountByDepth(1) } returns 10
        val expectedEntity = PromptEntity("L1_001", "Q1", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1)
        coEvery { promptDao.getNextEligiblePrompt(1, null) } returns expectedEntity

        val result = repository.getNewPrompt(progressionCount = 0)

        assertEquals("L1_001", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(1, null) }
    }

    @Test
    fun `getNewPrompt should select depth level 2 for progression 3`() = runBlocking {
        coEvery { promptDao.getUnconsumedCountByDepth(2) } returns 10
        val expectedEntity = PromptEntity("L2_001", "Q2", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 2)
        coEvery { promptDao.getNextEligiblePrompt(2, null) } returns expectedEntity

        val result = repository.getNewPrompt(progressionCount = 3)

        assertEquals("L2_001", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(2, null) }
    }

    @Test
    fun `getNewPrompt with excludedPromptId should pass excluded id to DAO`() = runBlocking {
        coEvery { promptDao.getUnconsumedCountByDepth(1) } returns 10
        val expectedEntity = PromptEntity("L1_002", "Q2", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1)
        coEvery { promptDao.getNextEligiblePrompt(1, "L1_001") } returns expectedEntity

        val result = repository.getNewPrompt(progressionCount = 0, excludedPromptId = "L1_001")

        assertEquals("L1_002", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(1, "L1_001") }
    }

    @Test
    fun `getNewPrompt should trigger depth-scoped recycling when all prompts at depth are consumed`() = runBlocking {
        coEvery { promptDao.getUnconsumedCountByDepth(1) } returnsMany listOf(0, 10)
        coEvery { promptDao.getPromptCountByDepth(1) } returns 10
        coEvery { promptDao.getNextEligiblePrompt(1, null) } returnsMany listOf(null, PromptEntity("L1_001", "Q1", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1))

        val result = repository.getNewPrompt(progressionCount = 0)

        assertEquals("L1_001", result?.id)
        coVerify { promptDao.resetConsumedPromptsForDepth(1) }
        coVerify(exactly = 0) { promptDao.resetConsumedPrompts() }
    }

    @Test
    fun `getNewPrompt should fallback to excluded prompt when it is the only unconsumed option`() = runBlocking {
        coEvery { promptDao.getUnconsumedCountByDepth(1) } returns 1
        val excludedEntity = PromptEntity("L1_001", "Q1", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1)
        coEvery { promptDao.getNextEligiblePrompt(1, "L1_001") } returns null
        coEvery { promptDao.getNextEligiblePrompt(1, null) } returns excludedEntity

        val result = repository.getNewPrompt(progressionCount = 0, excludedPromptId = "L1_001")

        assertEquals("L1_001", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(1, "L1_001") }
        coVerify { promptDao.getNextEligiblePrompt(1, null) }
    }

    @Test
    fun `markAsConsumed should ignore blank, nav_, ai_, safety_ and unapproved prompt IDs`() = runBlocking {
        repository.markAsConsumed("")
        repository.markAsConsumed("   ")
        repository.markAsConsumed("nav_1712345678")
        repository.markAsConsumed("nav_test")
        repository.markAsConsumed("ai_123456789")
        repository.markAsConsumed("safety_high")
        repository.markAsConsumed("unapproved_unknown_id")

        coVerify(exactly = 0) { promptDao.markAsConsumed(any()) }

        repository.markAsConsumed("valid_id")
        coVerify(exactly = 1) { promptDao.markAsConsumed("valid_id") }
    }

    @Test
    fun `getApprovedPromptById rejects synthetic and unapproved IDs`() = runBlocking {
        repository.syncPromptsFromAsset()

        assertNull(repository.getApprovedPromptById("ai_12345"))
        assertNull(repository.getApprovedPromptById("nav_9999"))
        assertNull(repository.getApprovedPromptById("safety_high"))
        assertNull(repository.getApprovedPromptById("unknown_id"))

        val approved = repository.getApprovedPromptById("valid_id")
        assertEquals("valid_id", approved?.id)
        assertEquals("Valid Question", approved?.question)
    }

    @Test
    fun `getApprovedPromptById rejects corrupted DB record with mismatched question text`() = runBlocking {
        repository.syncPromptsFromAsset()

        // DB contains a record with valid ID 'valid_id' but corrupted question text
        val corruptedEntity = PromptEntity("valid_id", "Hacked Text", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1)
        coEvery { promptDao.getPromptById("valid_id") } returns corruptedEntity

        val result = repository.getApprovedPromptById("valid_id")
        // Should ignore corrupted DB text and return canonical approved question
        assertEquals("Valid Question", result?.question)
    }

    @Test
    fun `syncPromptsFromAsset syncs when prompts table is empty even if isSynced was true`() = runBlocking {
        coEvery { promptDao.getPromptCount() } returns 0
        coEvery { promptDao.getAllPromptsList() } returns emptyList()

        repository.syncPromptsFromAsset()

        coVerify { promptDao.insertPrompts(any()) }
    }

    @Test
    fun `resetSyncState allows subsequent sync`() = runBlocking {
        coEvery { promptDao.getPromptCount() } returnsMany listOf(10, 0)
        coEvery { promptDao.getAllPromptsList() } returns emptyList()

        repository.syncPromptsFromAsset()
        repository.resetSyncState()
        repository.syncPromptsFromAsset()

        coVerify { promptDao.insertPrompts(any()) }
    }
}
