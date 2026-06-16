package com.saurabh.artifact.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleTransitionTest {

    @Test
    fun `transitions are allowed in forward direction`() {
        // RECORDING (0) -> PROCESSING (1)
        assertTrue(ArtifactLifecycle.RECORDING.canTransitionTo(ArtifactLifecycle.PROCESSING))
        
        // REVIEW_REQUIRED (2) -> METADATA_REQUIRED (3)
        assertTrue(ArtifactLifecycle.REVIEW_REQUIRED.canTransitionTo(ArtifactLifecycle.METADATA_REQUIRED))
        
        // PUBLISHED (5) -> DELETING (7)
        assertTrue(ArtifactLifecycle.PUBLISHED.canTransitionTo(ArtifactLifecycle.DELETING))
    }

    @Test
    fun `transitions to same state are allowed`() {
        assertTrue(ArtifactLifecycle.PROCESSING.canTransitionTo(ArtifactLifecycle.PROCESSING))
        assertTrue(ArtifactLifecycle.PUBLISHED.canTransitionTo(ArtifactLifecycle.PUBLISHED))
    }

    @Test
    fun `transitions are blocked in backward direction`() {
        // PROCESSING (1) -> RECORDING (0)
        assertFalse(ArtifactLifecycle.PROCESSING.canTransitionTo(ArtifactLifecycle.RECORDING))
        
        // METADATA_REQUIRED (3) -> REVIEW_REQUIRED (2)
        assertFalse(ArtifactLifecycle.METADATA_REQUIRED.canTransitionTo(ArtifactLifecycle.REVIEW_REQUIRED))
        
        // PUBLISHED (5) -> READY_TO_PUBLISH (4)
        assertFalse(ArtifactLifecycle.PUBLISHED.canTransitionTo(ArtifactLifecycle.READY_TO_PUBLISH))
    }

    @Test
    fun `recovery mode bypasses transition rules`() {
        // Backward transition but with recovery flag
        assertTrue(ArtifactLifecycle.PUBLISHED.canTransitionTo(ArtifactLifecycle.RECORDING, isRecovery = true))
        assertTrue(ArtifactLifecycle.METADATA_REQUIRED.canTransitionTo(ArtifactLifecycle.REVIEW_REQUIRED, isRecovery = true))
    }
}
