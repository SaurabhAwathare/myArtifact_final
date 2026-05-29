package com.saurabh.artifact.domain

import com.saurabh.artifact.model.ModerationWarning
import com.saurabh.artifact.model.UsernameValidationResult
import com.saurabh.artifact.model.ValidationReason
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A multi-layer moderation engine for usernames.
 * Protects anonymity, emotional safety, and platform atmosphere.
 */
@Singleton
class UsernameValidator @Inject constructor(
    private val identityScout: IdentityScout
) {

    private val reservedNames = setOf("admin", "moderator", "support", "official", "artifact", "system", "anonymous")
    
    // Heuristic lists for safety and atmosphere
    private val safetyBlocklist = listOf("kill", "hate", "hurt", "attack", "death", "die", "murder", "blood")
    private val negativeToneList = listOf("loser", "ugly", "stupid", "worthless", "depressed", "failure", "hate_myself")

    fun validate(username: String, realName: String? = null, email: String? = null): UsernameValidationResult {
        if (username.isBlank()) return UsernameValidationResult(isValid = false)

        val normalized = username.lowercase(Locale.ROOT).trim()

        // Layer 1: Format Validation
        validateFormat(normalized)?.let { return it }

        val warnings = mutableListOf<ModerationWarning>()

        // Layer 2: Privacy Detection (via IdentityScout)
        warnings.addAll(identityScout.detectLeaks(username, realName, email))

        // Layer 3: Safety & Reserved Names
        checkSafety(normalized)?.let { warnings.add(it) }

        // Layer 4: Emotional Atmosphere
        checkAtmosphere(normalized)?.let { warnings.add(it) }

        val riskScore = identityScout.calculateRiskScore(warnings)

        return UsernameValidationResult(
            isValid = warnings.isEmpty(),
            reason = warnings.firstOrNull()?.reason,
            warnings = warnings,
            riskScore = riskScore
        )
    }

    private fun validateFormat(username: String): UsernameValidationResult? {
        if (username.length < 3) return UsernameValidationResult(isValid = false, reason = ValidationReason.TOO_SHORT)
        if (username.length > 24) return UsernameValidationResult(isValid = false, reason = ValidationReason.TOO_LONG)
        
        val regex = Regex("^[a-z0-9_@.]+$")
        if (!regex.matches(username)) {
            return UsernameValidationResult(isValid = false, reason = ValidationReason.INVALID_CHARACTERS)
        }
        return null
    }

    private fun checkSafety(username: String): ModerationWarning? {
        if (reservedNames.contains(username)) {
            return ModerationWarning(
                ValidationReason.RESERVED_NAME,
                "This name is reserved for system use."
            )
        }

        for (blocked in safetyBlocklist) {
            if (username.contains(blocked)) {
                return ModerationWarning(
                    ValidationReason.HATEFUL_LANGUAGE,
                    "Let's keep the atmosphere respectful. Avoid violent or hateful language."
                )
            }
        }

        return null
    }

    private fun checkAtmosphere(username: String): ModerationWarning? {
        for (negative in negativeToneList) {
            if (username.contains(negative)) {
                return ModerationWarning(
                    ValidationReason.OVERLY_NEGATIVE,
                    "We value a reflective and kind environment. Try a more neutral or positive name."
                )
            }
        }
        return null
    }
}
