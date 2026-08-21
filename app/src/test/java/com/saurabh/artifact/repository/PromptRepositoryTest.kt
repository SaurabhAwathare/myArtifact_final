package com.saurabh.artifact.repository

import android.content.Context
import com.saurabh.artifact.data.local.PromptDao
import com.saurabh.artifact.data.local.PromptEntity
import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PromptRepositoryTest {
    private val promptDao = mockk<PromptDao>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var repository: PromptRepository

    @Before
    fun setup() {
        repository = PromptRepository(
            promptDao = { promptDao },
            context = context
        )
    }

    @Test
    fun `getNewPrompt should select prompt based on depth level 1 for new users`() = runBlocking {
        coEvery { promptDao.getConsumedCount() } returns 0
        val expectedEntity = PromptEntity("L1_001", "Q1", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 1)
        coEvery { promptDao.getNextEligiblePrompt(1) } returns expectedEntity

        val result = repository.getNewPrompt()

        assertEquals("L1_001", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(1) }
    }

    @Test
    fun `getNewPrompt should select depth level 2 after 30 prompts`() = runBlocking {
        coEvery { promptDao.getConsumedCount() } returns 35
        val expectedEntity = PromptEntity("L2_001", "Q2", PromptCategory.GENERAL, EmotionalTone.GENTLE, depthLevel = 2)
        coEvery { promptDao.getNextEligiblePrompt(2) } returns expectedEntity

        val result = repository.getNewPrompt()

        assertEquals("L2_001", result?.id)
        coVerify { promptDao.getNextEligiblePrompt(2) }
    }

    @Test
    fun `markAsConsumed should delegate to DAO`() = runBlocking {
        repository.markAsConsumed("test_id")
        coVerify { promptDao.markAsConsumed("test_id") }
    }
}
