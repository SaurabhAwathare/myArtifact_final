package com.saurabh.artifact.service

import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.PromptRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Note: This test primarily verifies the fallback and validation logic in ReflectionAIService.
 * Testing the real GenerativeModel would require complex MockK setups for the Firebase SDK
 * which is often better handled with integration tests or high-level mocks.
 */
class ReflectionAIServiceTest {

    private lateinit var promptRepository: PromptRepository
    private lateinit var safetyEvaluator: SafetyEvaluator
    private lateinit var service: ReflectionAIServiceImpl

    @Before
    fun setup() {
        promptRepository = mockk()
        safetyEvaluator = SafetyEvaluator() // Use real evaluator for validation logic
        service = ReflectionAIServiceImpl(promptRepository, safetyEvaluator, mockk(relaxed = true))
    }

    @Test
    fun `generatePrompt returns fallback when AI fails`() = runTest {
        // Since we can't easily mock the lazy generativeModel without complex reflection,
        // it will naturally fail in a unit test environment (missing Firebase init),
        // triggering the fallback logic we want to test.
        
        val fallbackPrompt = ReflectionPrompt(
            id = "123",
            question = "How are you really?",
            category = PromptCategory.GENERAL,
            tone = EmotionalTone.REFLECTIVE
        )
        
        coEvery { promptRepository.getSmartFallback(any()) } returns fallbackPrompt
        
        val result = service.generatePrompt("Joy", null, "Morning")
        
        assertTrue(result.isSuccess)
        val prompt = result.getOrThrow()
        assertEquals(fallbackPrompt.question, prompt.question)
    }

    @Test
    fun `generatePrompt returns generic fallback when repository is empty`() = runTest {
        coEvery { promptRepository.getSmartFallback(any()) } returns null
        
        val result = service.generatePrompt("Joy", null, "Morning")
        
        assertTrue(result.isSuccess)
        val prompt = result.getOrThrow()
        assertEquals("fallback_generic", prompt.id)
        assertTrue(prompt.question.contains("resting on your heart"))
    }
}
