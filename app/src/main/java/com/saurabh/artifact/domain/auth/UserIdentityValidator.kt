package com.saurabh.artifact.domain.auth

import com.saurabh.artifact.model.CURRENT_SCHEMA_VERSION
import com.saurabh.artifact.model.User
import com.saurabh.artifact.util.UsernameGenerator

/**
 * Result of an identity integrity validation.
 */
data class IdentityValidationResult(
    val isValid: Boolean,
    val reasons: List<String> = emptyList()
)

/**
 * A pure, side-effect-free utility for checking user identity integrity.
 * This acts as the central audit mechanism for the Sigil system.
 */
object UserIdentityValidator {

    /**
     * Validates a user profile against identity invariants.
     */
    fun validate(user: User): IdentityValidationResult {
        val reasons = mutableListOf<String>()

        // 1. Schema Version Check
        if (user.schemaVersion < CURRENT_SCHEMA_VERSION) {
            reasons.add("LEGACY_SCHEMA_V${user.schemaVersion}")
        }

        // 2. Anonymous ID Invariant
        if (user.anonymousId.isBlank()) {
            reasons.add("MISSING_ANONYMOUS_ID")
        }

        // 3. Sigil Invariants
        val expectedSigil = UsernameGenerator.deriveSigil(user.anonymousId)
        if (user.anonymousSigil.isBlank()) {
            reasons.add("MISSING_ANONYMOUS_SIGIL")
        } else if (user.anonymousSigil != expectedSigil) {
            reasons.add("SIGIL_MISMATCH: expected=$expectedSigil, actual=${user.anonymousSigil}")
        }

        // 4. Username Invariants
        if (user.anonymousName.isBlank()) {
            reasons.add("MISSING_ANONYMOUS_NAME")
        } else {
            val nameError = UsernameGenerator.validate(user.anonymousName)
            if (nameError != null) {
                reasons.add("INVALID_ANONYMOUS_NAME: $nameError")
            }
        }

        // 5. Avatar Invariants
        if (user.avatarSeed.isBlank()) {
            reasons.add("MISSING_AVATAR_SEED")
        }
        
        // Basic AvatarConfig version check (Defense in Depth)
        if (user.avatarConfig.version < 2) {
            reasons.add("LEGACY_AVATAR_CONFIG_V${user.avatarConfig.version}")
        }

        return IdentityValidationResult(
            isValid = reasons.isEmpty(),
            reasons = reasons
        )
    }
}
