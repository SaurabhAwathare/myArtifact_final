package com.saurabh.artifact.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareEligibilityTest {

    @Test
    fun `canShare returns true for public non-draft`() {
        assertTrue(ShareEligibility.canShare(isPublic = true, isDraft = false))
    }

    @Test
    fun `canShare returns false for private artifact`() {
        assertFalse(ShareEligibility.canShare(isPublic = false, isDraft = false))
    }

    @Test
    fun `canShare returns false for draft artifact`() {
        assertFalse(ShareEligibility.canShare(isPublic = true, isDraft = true))
    }

    @Test
    fun `canShare returns false for private draft artifact`() {
        assertFalse(ShareEligibility.canShare(isPublic = false, isDraft = true))
    }
}
