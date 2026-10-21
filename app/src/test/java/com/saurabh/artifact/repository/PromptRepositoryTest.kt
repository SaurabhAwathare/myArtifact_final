package com.saurabh.artifact.repository

import android.content.Context
import android.util.Log
import com.saurabh.artifact.data.local.PromptDao
import com.saurabh.artifact.data.local.PromptEntity
import com.saurabh.artifact.data.local.UserConsumedPromptDao
import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.data.local.UserConsumedPromptEntity
import com.saurabh.artifact.model.PromptSelectionResult
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
    private val userConsumedPromptDao = mockk<UserConsumedPromptDao>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var repository: PromptRepository

    private val sampleJsonContent = """
        [
            {"id":"B1_001","question":"Q1","category":"GENERAL","tone":"GENTLE","depthLevel":1},
            {"id":"B1_002","question":"Q2","category":"GENERAL","tone":"GENTLE","depthLevel":1},
            {"id":"B2_001","question":"Q2","category":"GENERAL","tone":"GENTLE","depthLevel":2},
            {"id":"B1_003","question":"Valid Question","category":"GENERAL","tone":"GENTLE","depthLevel":1},
            {"id":"B1_004","question":"Q1","category":"GENERAL","tone":"GENTLE","depthLevel":1}
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
            userConsumedPromptDao = { userConsumedPromptDao },
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
        val expectedEntity = PromptEntity("B1_001", "Q1", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1)
        coEvery { promptDao.getNextEligiblePrompt(1, null) } returns expectedEntity

        val result = repository.getNewPrompt(progressionCount = 0)

        assertEquals("B1_001", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(1, null) }
    }

    @Test
    fun `getNewPrompt should select depth level 2 for progression 3`() = runBlocking {
        coEvery { promptDao.getUnconsumedCountByDepth(2) } returns 10
        val expectedEntity = PromptEntity("B2_001", "Q2", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 2)
        coEvery { promptDao.getNextEligiblePrompt(2, null) } returns expectedEntity

        val result = repository.getNewPrompt(progressionCount = 3)

        assertEquals("B2_001", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(2, null) }
    }

    @Test
    fun `getNewPrompt with excludedPromptId should pass excluded id to DAO`() = runBlocking {
        coEvery { promptDao.getUnconsumedCountByDepth(1) } returns 10
        val expectedEntity = PromptEntity("B1_002", "Q2", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1)
        coEvery { promptDao.getNextEligiblePrompt(1, "B1_001") } returns expectedEntity

        val result = repository.getNewPrompt(progressionCount = 0, excludedPromptId = "B1_001")

        assertEquals("B1_002", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(1, "B1_001") }
    }

    @Test
    fun `deriveBatchFromId correctly derives batch numbers and validates format`() {
        assertEquals(1, repository.deriveBatchFromId("B1_001"))
        assertEquals(8, repository.deriveBatchFromId("B8_250"))
        assertEquals(3, repository.deriveBatchFromId("B1_001", explicitBatch = 3))

        try {
            repository.deriveBatchFromId("")
            assertTrue("Expected IllegalArgumentException for empty ID", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cannot be blank"))
        }

        try {
            repository.deriveBatchFromId("B9_001")
            assertTrue("Expected IllegalArgumentException for B9_001", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid prompt ID format"))
        }

        try {
            repository.deriveBatchFromId("invalid_id")
            assertTrue("Expected IllegalArgumentException for invalid_id", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid prompt ID format"))
        }
    }

    @Test
    fun `getPromptForBatch selects unconsumed prompt for specified batch and userId`() = runBlocking {
        coEvery { promptDao.getPromptCount() } returns 10
        coEvery { promptDao.getNextUnconsumedPromptInBatch("user_123", 1) } returns PromptEntity("B1_001", "Q1", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1, batchNumber = 1)

        val result = repository.getPromptForBatch("user_123", 1)

        assertTrue(result is PromptSelectionResult.Success)
        val success = result as PromptSelectionResult.Success
        assertEquals("B1_001", success.prompt.id)
        assertEquals(1, success.batchNumber)
        coVerify { promptDao.getNextUnconsumedPromptInBatch("user_123", 1) }
    }

    @Test
    fun `getPromptForBatch rejects blank userId and invalid batch numbers`() = runBlocking {
        val blankUserResult = repository.getPromptForBatch("", 1)
        assertTrue(blankUserResult is PromptSelectionResult.Error)

        val invalidBatchResult = repository.getPromptForBatch("user_123", 9)
        assertTrue(invalidBatchResult is PromptSelectionResult.Error)
    }

    @Test
    fun `getPromptForBatch returns BatchExhausted when target batch is empty but catalog has unconsumed prompts`() = runBlocking {
        coEvery { promptDao.getPromptCount() } returns 10
        coEvery { promptDao.getNextUnconsumedPromptInBatch("user_123", 1) } returns null
        coEvery { promptDao.getTotalUnconsumedCount("user_123") } returns 5

        val result = repository.getPromptForBatch("user_123", 1)

        assertEquals(PromptSelectionResult.BatchExhausted, result)
    }

    @Test
    fun `getNextPromptInProgression begins at Batch 1 and advances sequentially skipping exhausted batches`() = runBlocking {
        coEvery { promptDao.getPromptCount() } returns 10
        coEvery { promptDao.getTotalUnconsumedCount("user_123") } returns 5

        coEvery { promptDao.getNextUnconsumedPromptInBatch("user_123", 1) } returns null
        coEvery { promptDao.getNextUnconsumedPromptInBatch("user_123", 2) } returns null
        coEvery { promptDao.getNextUnconsumedPromptInBatch("user_123", 3) } returns PromptEntity("B3_001", "Q3", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1, batchNumber = 3)

        val result = repository.getNextPromptInProgression("user_123", startBatchNumber = 1)

        assertTrue(result is PromptSelectionResult.Success)
        val success = result as PromptSelectionResult.Success
        assertEquals("B3_001", success.prompt.id)
        assertEquals(3, success.batchNumber)

        coVerify { promptDao.getNextUnconsumedPromptInBatch("user_123", 1) }
        coVerify { promptDao.getNextUnconsumedPromptInBatch("user_123", 2) }
        coVerify { promptDao.getNextUnconsumedPromptInBatch("user_123", 3) }
    }

    @Test
    fun `getNextPromptInProgression returns CatalogExhausted when no unconsumed prompts remain and never recycles`() = runBlocking {
        coEvery { promptDao.getPromptCount() } returns 10
        coEvery { promptDao.getTotalUnconsumedCount("user_123") } returns 0

        val result = repository.getNextPromptInProgression("user_123", startBatchNumber = 1)

        assertEquals(PromptSelectionResult.CatalogExhausted, result)
        coVerify(exactly = 0) { promptDao.resetConsumedPromptsForDepth(any()) }
        coVerify(exactly = 0) { promptDao.resetConsumedPrompts() }
    }

    @Test
    fun `markPromptConsumed permanently marks prompt as consumed for specific UID idempotently`() = runBlocking {
        repository.syncPromptsFromAsset()

        val userId = "user_123"
        val promptId = "B1_003"

        coEvery { userConsumedPromptDao.isPromptConsumed(userId, promptId) } returns false

        val result1 = repository.markPromptConsumed(userId, promptId)
        assertTrue(result1.isSuccess)

        val slot = slot<UserConsumedPromptEntity>()
        coVerify { userConsumedPromptDao.markConsumed(capture(slot)) }
        assertEquals(userId, slot.captured.userId)
        assertEquals(promptId, slot.captured.promptId)
        assertEquals("PENDING", slot.captured.syncState)

        val result2 = repository.markPromptConsumed(userId, promptId)
        assertTrue(result2.isSuccess)
    }

    @Test
    fun `markPromptConsumed ignores blank, nav_ and unapproved prompt IDs`() = runBlocking {
        repository.syncPromptsFromAsset()

        repository.markPromptConsumed("user_123", "")
        repository.markPromptConsumed("user_123", "   ")
        repository.markPromptConsumed("user_123", "nav_home_feed")
        repository.markPromptConsumed("user_123", "unapproved_999")

        coVerify(exactly = 0) { userConsumedPromptDao.markConsumed(any()) }
    }

    @Test
    fun `displaying prompt does not consume it`() = runBlocking {
        coEvery { promptDao.getPromptCount() } returns 10
        coEvery { promptDao.getNextUnconsumedPromptInBatch("user_123", 1) } returns PromptEntity("B1_001", "Q1", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1, batchNumber = 1)

        repository.getPromptForBatch("user_123", 1)
        repository.getNextPromptInProgression("user_123", 1)

        coVerify(exactly = 0) { userConsumedPromptDao.markConsumed(any()) }
        coVerify(exactly = 0) { promptDao.markAsConsumed(any()) }
    }

    @Test
    fun `getNewPrompt should fallback to excluded prompt when it is the only unconsumed option`() = runBlocking {
        coEvery { promptDao.getUnconsumedCountByDepth(1) } returns 1
        val excludedEntity = PromptEntity("B1_001", "Q1", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1)
        coEvery { promptDao.getNextEligiblePrompt(1, "B1_001") } returns null
        coEvery { promptDao.getNextEligiblePrompt(1, null) } returns excludedEntity

        val result = repository.getNewPrompt(progressionCount = 0, excludedPromptId = "B1_001")

        assertEquals("B1_001", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(1, "B1_001") }
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

        repository.markAsConsumed("B1_003")
        coVerify(exactly = 1) { promptDao.markAsConsumed("B1_003") }
    }

    @Test
    fun `getApprovedPromptById rejects synthetic and unapproved IDs`() = runBlocking {
        repository.syncPromptsFromAsset()

        assertNull(repository.getApprovedPromptById("ai_12345"))
        assertNull(repository.getApprovedPromptById("nav_9999"))
        assertNull(repository.getApprovedPromptById("safety_high"))
        assertNull(repository.getApprovedPromptById("unknown_id"))

        val approved = repository.getApprovedPromptById("B1_003")
        assertEquals("B1_003", approved?.id)
        assertEquals("Valid Question", approved?.question)
    }

    @Test
    fun `getApprovedPromptById rejects corrupted DB record with mismatched question text`() = runBlocking {
        repository.syncPromptsFromAsset()

        // DB contains a record with valid ID 'B1_003' but corrupted question text
        val corruptedEntity = PromptEntity("B1_003", "Hacked Text", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1)
        coEvery { promptDao.getPromptById("B1_003") } returns corruptedEntity

        val result = repository.getApprovedPromptById("B1_003")
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
