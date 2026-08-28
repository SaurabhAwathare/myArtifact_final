package com.saurabh.artifact.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPromptBankTest {

    @Test
    fun `getRandomPrompt returns non-empty string`() {
        val prompt = CommentPromptBank.getRandomPrompt()
        assertNotNull(prompt)
        assertTrue(prompt.isNotEmpty())
    }

    @Test
    fun `getStablePrompt returns same prompt for same ID`() {
        val id = "artifact_123"
        val prompt1 = CommentPromptBank.getStablePrompt(id)
        val prompt2 = CommentPromptBank.getStablePrompt(id)
        assertEquals(prompt1, prompt2)
    }

    @Test
    fun `getStablePrompt returns different prompts for different IDs`() {
        // Since we have ~10 prompts, there's a chance of collision, 
        // but with 100 random IDs we should see variety.
        val prompts = (1..100).map { CommentPromptBank.getStablePrompt("id_$it") }.toSet()
        assertTrue("Should have multiple unique prompts", prompts.size > 1)
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
