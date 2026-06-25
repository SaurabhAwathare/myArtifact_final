package com.saurabh.artifact.domain.auth

import com.saurabh.artifact.model.CURRENT_SCHEMA_VERSION
import com.saurabh.artifact.model.User
import com.saurabh.artifact.model.AvatarConfig
import org.junit.Assert.*
import org.junit.Test

class UserIdentityValidatorTest {

    private val healthyUser = User(
        id = "uid123",
        anonymousId = "usr_ABC12",
        anonymousName = "Quiet Soul",
        anonymousSigil = "12",
        avatarSeed = "seed123",
        avatarConfig = AvatarConfig(version = 2),
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
    fun testValidate_MissingAvatarSeed_ReturnsInvalid() {
        val user = healthyUser.copy(avatarSeed = "")
        val result = UserIdentityValidator.validate(user)
        assertFalse(result.isValid)
        assertTrue(result.reasons.contains("MISSING_AVATAR_SEED"))
    }

    @Test
    fun testValidate_LegacyAvatarConfig_ReturnsInvalid() {
        val user = healthyUser.copy(avatarConfig = AvatarConfig(version = 1))
        val result = UserIdentityValidator.validate(user)
        assertFalse(result.isValid)
        assertTrue(result.reasons.contains("LEGACY_AVATAR_CONFIG_V1"))
    }
}
