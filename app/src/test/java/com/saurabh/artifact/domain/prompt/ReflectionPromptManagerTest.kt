package com.saurabh.artifact.domain.prompt

import com.saurabh.artifact.model.PromptCategory
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
}
