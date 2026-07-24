package com.saurabh.artifact.domain.prompt

import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.service.ReflectionAIService
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ReflectionPromptManagerTest {
    private val aiService = mockk<ReflectionAIService>(relaxed = true)
    private lateinit var manager: ReflectionPromptManager

    @Before
    fun setup() {
        manager = ReflectionPromptManager(
            aiService = { aiService }
        )
    }

    @Test
    fun `getSmartReflectionPrompt should return prompt from AI service`() = runBlocking {
        val expected = ReflectionPrompt(id = "1", question = "How are you?", category = PromptCategory.GENERAL)
        coEvery { aiService.generatePrompt(any(), any(), any()) } returns Result.success(expected)

        val result = manager.getSmartReflectionPrompt("Joy", "Context", "Morning")

        assertEquals(expected, result)
    }

    @Test
    fun `getSmartReflectionPrompt should return fallback if AI service fails`() = runBlocking {
        coEvery { aiService.generatePrompt(any(), any(), any()) } returns Result.failure(Exception("AI Error"))

        val result = manager.getSmartReflectionPrompt("Joy", "Context", "Morning")

        assertEquals(PromptCategory.GENERAL, result.category)
        assert(result.id.startsWith("fallback_"))
    }
}
