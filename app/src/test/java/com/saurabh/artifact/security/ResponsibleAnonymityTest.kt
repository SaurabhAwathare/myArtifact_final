package com.saurabh.artifact.security

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.AuthorSnapshot
import com.saurabh.artifact.model.User
import org.junit.Assert.*
import org.junit.Test

class ResponsibleAnonymityTest {

    @Test
    fun `AuthorSnapshot fromUser should not contain Firebase UID`() {
        val user = User(
            id = "firebase_uid_123",
            anonymousId = "usr_ABCD",
            anonymousName = "Quiet Shadow",
            anonymousSigil = "sigil_data",
            sigilSeed = "seed_123"
        )
        
        val snapshot = AuthorSnapshot.fromUser(user)
        
        assertEquals("usr_ABCD", snapshot.anonymousId)
        assertEquals("Quiet Shadow", snapshot.name)
        // Verify no field in AuthorSnapshot contains the UID
        assertNotEquals(user.id, snapshot.anonymousId)
        assertNotEquals(user.id, snapshot.name)
        assertNotEquals(user.id, snapshot.sigil)
        assertNotEquals(user.id, snapshot.sigilSeed)
    }

    @Test
    fun `Artifact authorSigilConfig should not fallback to userId if anonymousId is available`() {
        val artifact = Artifact(
            id = "artifact_123",
            userId = "leaked_uid",
            author = AuthorSnapshot(
                anonymousId = "persona_A",
                sigilSeed = ""
            )
        )
        
        val config = artifact.authorSigilConfig
        assertNotEquals("leaked_uid", config.seed)
        assertEquals("persona_A", config.seed)
    }

    @Test
    fun `Artifact authorSigilConfig should fallback to artifactId if everything else is missing`() {
        val artifact = Artifact(
            id = "artifact_123",
            userId = "leaked_uid",
            author = AuthorSnapshot(
                anonymousId = "",
                sigilSeed = ""
            )
        )
        
        val config = artifact.authorSigilConfig
        assertNotEquals("leaked_uid", config.seed)
        assertEquals("artifact_123", config.seed)
    }
}
