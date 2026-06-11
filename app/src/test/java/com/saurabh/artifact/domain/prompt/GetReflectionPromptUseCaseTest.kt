package com.saurabh.artifact.domain.prompt

import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.service.AdManager
import com.saurabh.artifact.service.SafetyEvaluator
import com.saurabh.artifact.service.SafetyLevel
import com.saurabh.artifact.service.SafetyResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetReflectionPromptUseCaseTest {

    private val artifactRepository = mockk<ArtifactRepository>()
    private val safetyEvaluator = mockk<SafetyEvaluator>()
    private val adManager = mockk<AdManager>(relaxed = true)
    
    private lateinit var useCase: GetReflectionPromptUseCase

    @Before
    fun setup() {
        useCase = GetReflectionPromptUseCase(artifactRepository, safetyEvaluator, adManager)
    }

    @Test
    fun `invoke with high risk context returns safety prompt immediately`() = runTest {
        // Arrange
        val context = "I want to end it all"
        val safetyPrompt = ReflectionPrompt(id = "safety", question = "Help is here", category = PromptCategory.GENERAL)
        val safetyResult = SafetyResult(
            level = SafetyLevel.HIGH,
            isCrisis = true,
            suggestedPrompt = safetyPrompt
        )
        
        every { safetyEvaluator.evaluate(context) } returns safetyResult
        
        // Act
        val result = useCase.invoke(null, context)
        
        // Assert
        assertEquals(safetyPrompt, result.prompt)
        assertEquals(SafetyLevel.HIGH, result.safetyLevel)
        assertTrue(result.isCrisis)
        
        // Verify AI service was NEVER called
        coVerify(exactly = 0) { artifactRepository.getSmartReflectionPrompt(any(), any(), any()) }
    }

    @Test
    fun `invoke with low risk context calls AI service`() = runTest {
        // Arrange
        val context = "I had a great day at the park"
        val aiPrompt = ReflectionPrompt(id = "ai", question = "What made it great?", category = PromptCategory.AI_GUIDED)
        val safetyResult = SafetyResult(level = SafetyLevel.LOW, isCrisis = false)
        
        every { safetyEvaluator.evaluate(context) } returns safetyResult
        coEvery { artifactRepository.getSmartReflectionPrompt(any(), eq(context), any()) } returns aiPrompt
        
        // Act
        val result = useCase.invoke("Joy", context)
        
        // Assert
        assertEquals(aiPrompt, result.prompt)
        assertEquals(SafetyLevel.LOW, result.safetyLevel)
        assertEquals(false, result.isCrisis)
        
        // Verify AI service WAS called
        coVerify(exactly = 1) { artifactRepository.getSmartReflectionPrompt("Joy", context, any()) }
    }

    @Test
    fun `invoke with medium risk context and suggestion returns suggestion`() = runTest {
        // Arrange
        val context = "I feel so alone"
        val safetyPrompt = ReflectionPrompt(id = "safety_med", question = "Who can you reach out to?", category = PromptCategory.GENERAL)
        val safetyResult = SafetyResult(
            level = SafetyLevel.MEDIUM,
            isCrisis = false,
            suggestedPrompt = safetyPrompt
        )
        
        every { safetyEvaluator.evaluate(context) } returns safetyResult
        
        // Act
        val result = useCase.invoke(null, context)
        
        // Assert
        assertEquals(safetyPrompt, result.prompt)
        assertEquals(SafetyLevel.MEDIUM, result.safetyLevel)
        
        // Verify AI service was NEVER called because we had a suggestion
        coVerify(exactly = 0) { artifactRepository.getSmartReflectionPrompt(any(), any(), any()) }
    }
}
