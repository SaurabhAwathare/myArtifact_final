package com.saurabh.artifact.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleTransitionTest {

    @Test
    fun `transitions are allowed in matrix direction`() {
        // RECORDING -> PROCESSING
        assertTrue(ArtifactLifecycle.RECORDING.canTransitionTo(ArtifactLifecycle.PROCESSING))
        
        // PROCESSING -> REVIEW_REQUIRED
        assertTrue(ArtifactLifecycle.PROCESSING.canTransitionTo(ArtifactLifecycle.REVIEW_REQUIRED))
        
        // REVIEW_REQUIRED -> METADATA_REQUIRED
        assertTrue(ArtifactLifecycle.REVIEW_REQUIRED.canTransitionTo(ArtifactLifecycle.METADATA_REQUIRED))
        
        // PUBLISHED -> DELETING
        assertTrue(ArtifactLifecycle.PUBLISHED.canTransitionTo(ArtifactLifecycle.DELETING))
    }

    @Test
    fun `backward transitions are blocked by default`() {
        // METADATA_REQUIRED -> REVIEW_REQUIRED
        assertFalse(ArtifactLifecycle.METADATA_REQUIRED.canTransitionTo(ArtifactLifecycle.REVIEW_REQUIRED))

        // READY_TO_PUBLISH -> METADATA_REQUIRED
        assertFalse(ArtifactLifecycle.READY_TO_PUBLISH.canTransitionTo(ArtifactLifecycle.METADATA_REQUIRED))
    }

    @Test
    fun `transitions to same state are allowed`() {
        assertTrue(ArtifactLifecycle.PROCESSING.canTransitionTo(ArtifactLifecycle.PROCESSING))
        assertTrue(ArtifactLifecycle.PUBLISHED.canTransitionTo(ArtifactLifecycle.PUBLISHED))
    }

    @Test
    fun `invalid transitions are blocked`() {
        // PROCESSING -> RECORDING (Backward)
        assertFalse(ArtifactLifecycle.PROCESSING.canTransitionTo(ArtifactLifecycle.RECORDING))
        
        // RECORDING -> REVIEW_REQUIRED (Skip PROCESSING)
        assertFalse(ArtifactLifecycle.RECORDING.canTransitionTo(ArtifactLifecycle.REVIEW_REQUIRED))
        
        // PUBLISHED -> READY_TO_PUBLISH (Backward)
        assertFalse(ArtifactLifecycle.PUBLISHED.canTransitionTo(ArtifactLifecycle.READY_TO_PUBLISH))
        
        // DELETED -> DELETING (Backward)
        assertFalse(ArtifactLifecycle.DELETED.canTransitionTo(ArtifactLifecycle.DELETING))
    }

    @Test
    fun `recovery mode bypasses transition rules`() {
        // Backward transition but with recovery flag
        assertTrue(ArtifactLifecycle.PUBLISHED.canTransitionTo(ArtifactLifecycle.RECORDING, isRecovery = true))
        assertTrue(ArtifactLifecycle.METADATA_REQUIRED.canTransitionTo(ArtifactLifecycle.RECORDING, isRecovery = true))
    }
}
