package com.saurabh.artifact.domain.prompt

import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.PromptSelectionResult
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.PromptRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ReflectionPromptManagerTest {
    private val promptRepository = mockk<PromptRepository>(relaxed = true)
    private lateinit var manager: ReflectionPromptManager

    @Before
    fun setup() {
        manager = ReflectionPromptManager(
            promptRepository = promptRepository
        )
    }

    @Test
    fun `getNextPrompt should return prompt from repository with progressionCount`() = runBlocking {
        val expected = ReflectionPrompt(id = "1", question = "How are you?", category = PromptCategory.GENERAL, depthLevel = 1)
        coEvery { promptRepository.getNewPrompt(5, null) } returns expected

        val result = manager.getNextPrompt(progressionCount = 5)

        assertEquals(expected, result)
        coVerify { promptRepository.getNewPrompt(5, null) }
    }

    @Test
    fun `getNextPrompt should return null if repository returns null`() = runBlocking {
        coEvery { promptRepository.getNewPrompt(0, null) } returns null

        val result = manager.getNextPrompt(progressionCount = 0)

        assertNull(result)
    }

    @Test
    fun `getPromptForBatch should delegate to promptRepository getPromptForBatch`() = runBlocking {
        val expectedPrompt = ReflectionPrompt(id = "B1_001", question = "Test batch 1?", category = PromptCategory.GENERAL, batchNumber = 1)
        val expectedResult = PromptSelectionResult.Success(expectedPrompt, 1)
        coEvery { promptRepository.getPromptForBatch("user_123", 1) } returns expectedResult

        val result = manager.getPromptForBatch("user_123", 1)

        assertEquals(expectedResult, result)
        coVerify(exactly = 1) { promptRepository.getPromptForBatch("user_123", 1) }
    }
}
