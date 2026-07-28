package com.saurabh.artifact.domain.auth

import com.google.firebase.firestore.DocumentSnapshot
import com.saurabh.artifact.model.CURRENT_SCHEMA_VERSION
import com.saurabh.artifact.model.sigil.SigilVariant
import android.util.Log
import com.saurabh.artifact.model.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProfileRepairServiceTest {

    private val service = ProfileRepairService()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    private fun mockSnapshot(id: String, data: Map<String, Any?> = emptyMap()): DocumentSnapshot {
        val snapshot = mockk<DocumentSnapshot>()
        every { snapshot.id } returns id
        every { snapshot.data } returns data
        
        // Mock contains for cleanup logic (Requirement 1)
        val legacyKeys = setOf("avatarSeed", "avatarColor", "avatarConfig", "followersCount", "followingCount")
        every { snapshot.contains(any<String>()) } answers {
            legacyKeys.contains(arg<String>(0)) && data.containsKey(arg<String>(0))
        }
        
        return snapshot
    }

    @Test
    fun `detect legacy fields for cleanup`() {
        val snapshot = mockSnapshot("user_legacy", mapOf("avatarSeed" to "some_seed"))
        
        // Even if toObject succeeds, if legacy fields are present, it needs repair (cleanup)
        val healthyUser = User(id = "user_legacy", anonymousId = "usr_legacy", anonymousName = "Legacy Soul", sigilSeed = "seed")
        every { snapshot.toObject(User::class.java) } returns healthyUser

        val (_, needsRepair) = service.loadAndRepair(snapshot)

        assertTrue("Should trigger repair for cleanup when legacy fields exist", needsRepair)
    }

    @Test
    fun `idempotent when legacy fields removed`() {
        val snapshot = mockSnapshot("user_clean", mapOf("sigilSeed" to "seed"))

        val anonymousId = "usr_clean"
        val expectedSigil = com.saurabh.artifact.util.UsernameGenerator.deriveSigil(anonymousId)
        val healthyUser = User(
            id = "user_clean", 
            anonymousId = anonymousId, 
            anonymousName = "Clean Soul", 
            anonymousSigil = expectedSigil,
            sigilSeed = "seed",
            sigilConfig = com.saurabh.artifact.model.SigilConfig(seed = "seed", version = 3)
        )
        every { snapshot.toObject(User::class.java) } returns healthyUser

        val (_, needsRepair) = service.loadAndRepair(snapshot)

        assertFalse("Should not trigger repair if document is already clean and valid", needsRepair)
    }

    @Test
    fun `repair legacy avatar fields to sigil fields`() {
        val snapshot = mockSnapshot("user_legacy_data", mapOf(
            "avatarSeed" to "old_seed",
            "avatarColor" to "#123456",
            "avatarConfig" to mapOf("version" to 1, "seed" to "old_seed"),
            "anonymousId" to "usr_old",
            "anonymousName" to "Old Name"
        ))
        
        every { snapshot.toObject(User::class.java) } throws RuntimeException("Legacy")

        val (user, needsRepair) = service.loadAndRepair(snapshot)

        assertTrue(needsRepair)
        assertEquals("old_seed", user.sigilSeed)
        assertEquals("#123456", user.sigilColor)
        assertEquals(3, user.sigilConfig.version)
        assertEquals("old_seed", user.sigilConfig.seed)
    }

    @Test
    fun `mixed avatar and sigil fields triggers cleanup`() {
        val snapshot = mockSnapshot("user_mixed", mapOf("sigilSeed" to "new_seed", "avatarSeed" to "old_seed"))
        
        val userWithSigil = User(id = "user_mixed", anonymousId = "usr_mixed", sigilSeed = "new_seed")
        every { snapshot.toObject(User::class.java) } returns userWithSigil

        val (user, needsRepair) = service.loadAndRepair(snapshot)

        assertTrue("Mixed fields should trigger cleanup repair", needsRepair)
        assertEquals("new_seed", user.sigilSeed)
    }

    @Test
    fun `repair legacy schema version`() {
        val snapshot = mockSnapshot("user123", mapOf(
            "schemaVersion" to 1,
            "anonymousId" to "usr_123",
            "anonymousName" to "Old Soul"
        ))
        // Simulate crash on toObject
        every { snapshot.toObject(any<Class<*>>()) } throws RuntimeException("Migration Needed")

        val (user, needsRepair) = service.loadAndRepair(snapshot)

        assertTrue(needsRepair)
        assertEquals(CURRENT_SCHEMA_VERSION, user.schemaVersion)
        assertEquals("usr_123", user.anonymousId)
    }

    @Test
    fun `repair invalid enum value`() {
        val snapshot = mockSnapshot("user456", mapOf(
            "schemaVersion" to CURRENT_SCHEMA_VERSION,
            "sigilConfig" to mapOf(
                "variant" to "GLOW" // Invalid enum
            )
        ))
        every { snapshot.toObject(any<Class<*>>()) } throws RuntimeException("Enum mismatch")

        val (user, needsRepair) = service.loadAndRepair(snapshot)

        assertTrue(needsRepair)
        assertEquals(SigilVariant.LIGHT, user.sigilConfig.variant)
    }

    @Test
    fun `coerce string to long`() {
        val snapshot = mockSnapshot("user789", mapOf(
            "resonanceInCount" to "42" // String instead of Long
        ))
        every { snapshot.toObject(any<Class<*>>()) } throws RuntimeException("Type mismatch")

        val (user, needsRepair) = service.loadAndRepair(snapshot)

        assertTrue(needsRepair)
        assertEquals(42L, user.resonanceInCount)
    }

    @Test
    fun `handle missing nested object`() {
        val snapshot = mockSnapshot("user000", mapOf(
            "anonymousId" to "usr_000"
            // identityMetadata is missing
        ))
        every { snapshot.toObject(any<Class<*>>()) } throws RuntimeException("Missing object")

        val (user, needsRepair) = service.loadAndRepair(snapshot)

        assertTrue(needsRepair)
        assertNotNull(user.identityMetadata)
        assertEquals(0, user.identityMetadata.emergencyResetCount)
    }

    @Test
    fun `migration V1 to V3 preserves data`() {
        val snapshot = mockSnapshot("user_v1", mapOf(
            "schemaVersion" to 1,
            "anonymousId" to "usr_v1",
            "anonymousName" to "Original Name",
            "bio" to "My Bio",
            "resonanceInCount" to 100L
        ))
        // Simulate crash on toObject for legacy schema
        every { snapshot.toObject(any<Class<*>>()) } throws RuntimeException("Legacy")

        val (user, needsRepair) = service.loadAndRepair(snapshot)

        assertTrue(needsRepair)
        assertEquals(CURRENT_SCHEMA_VERSION, user.schemaVersion)
        assertEquals("Original Name", user.anonymousName)
        assertEquals("My Bio", user.bio)
        assertEquals(100L, user.resonanceInCount)
    }

    @Test
    fun `repair only touches corrupted fields`() {
        val snapshot = mockSnapshot("user_partial", mapOf(
            "schemaVersion" to CURRENT_SCHEMA_VERSION,
            "anonymousId" to "usr_partial",
            "anonymousName" to "Keep Me",
            "bio" to "Keep Me Too",
            "resonanceInCount" to "invalid" // Corrupted
        ))
        every { snapshot.toObject(any<Class<*>>()) } throws RuntimeException("Corrupted")

        val (user, needsRepair) = service.loadAndRepair(snapshot)

        assertTrue(needsRepair)
        assertEquals("Keep Me", user.anonymousName)
        assertEquals("Keep Me Too", user.bio)
        assertEquals(0L, user.resonanceInCount) // Repaired to default
    }
}
