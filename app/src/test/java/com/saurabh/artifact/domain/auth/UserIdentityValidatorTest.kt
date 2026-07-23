package com.saurabh.artifact.domain.auth

import com.saurabh.artifact.model.CURRENT_SCHEMA_VERSION
import com.saurabh.artifact.model.User
import com.saurabh.artifact.model.SigilConfig
import org.junit.Assert.*
import org.junit.Test

class UserIdentityValidatorTest {

    private val healthyUser = User(
        id = "uid123",
        anonymousId = "usr_ABC12",
        anonymousName = "Quiet Soul",
        anonymousSigil = "12",
        sigilSeed = "seed123",
        sigilConfig = SigilConfig(version = 3),
        schemaVersion = CURRENT_SCHEMA_VERSION
    )

    @Test
    fun testValidate_HealthyUser_ReturnsValid() {
        val result = UserIdentityValidator.validate(healthyUser)
        assertTrue(result.reasons.toString(), result.isValid)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun testValidate_LegacySchema_ReturnsInvalid() {
        val user = healthyUser.copy(schemaVersion = 1)
        val result = UserIdentityValidator.validate(user)
        assertFalse(result.isValid)
        assertTrue(result.reasons.contains("LEGACY_SCHEMA_V1"))
    }

    @Test
    fun testValidate_MissingAnonymousId_ReturnsInvalid() {
        val user = healthyUser.copy(anonymousId = "")
        val result = UserIdentityValidator.validate(user)
        assertFalse(result.isValid)
        assertTrue(result.reasons.contains("MISSING_ANONYMOUS_ID"))
        // Missing ID also causes sigil mismatch
        assertTrue(result.reasons.any { it.startsWith("SIGIL_MISMATCH") })
    }

    @Test
    fun testValidate_SigilMismatch_ReturnsInvalid() {
        val user = healthyUser.copy(anonymousSigil = "XX")
        val result = UserIdentityValidator.validate(user)
        assertFalse(result.isValid)
        assertTrue(result.reasons.any { it.startsWith("SIGIL_MISMATCH") })
    }

    @Test
    fun testValidate_InvalidName_ReturnsInvalid() {
        val user = healthyUser.copy(anonymousName = "no") // too short
        val result = UserIdentityValidator.validate(user)
        assertFalse(result.isValid)
        assertTrue(result.reasons.any { it.startsWith("INVALID_ANONYMOUS_NAME") })
    }

    @Test
    fun testValidate_MissingSigilSeed_ReturnsInvalid() {
        val user = healthyUser.copy(sigilSeed = "")
        val result = UserIdentityValidator.validate(user)
        assertFalse(result.isValid)
        assertTrue(result.reasons.contains("MISSING_SIGIL_SEED"))
    }

    @Test
    fun testValidate_LegacySigilConfig_ReturnsInvalid() {
        val user = healthyUser.copy(sigilConfig = SigilConfig(version = 1))
        val result = UserIdentityValidator.validate(user)
        assertFalse(result.isValid)
        assertTrue(result.reasons.contains("LEGACY_SIGIL_CONFIG_V1"))
    }
}
