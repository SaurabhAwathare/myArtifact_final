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
class UsernameValidator @Inject constructor() {

    private val reservedNames = setOf("admin", "moderator", "support", "official", "artifact", "system")
    
    // Heuristic lists for safety and atmosphere (Simplified for demo)
    private val safetyBlocklist = listOf("kill", "hate", "hurt", "attack", "death", "die")
    private val negativeToneList = listOf("loser", "ugly", "stupid", "worthless", "depressed")

    fun validate(username: String): UsernameValidationResult {
        if (username.isBlank()) return UsernameValidationResult(isValid = false)

        val normalized = username.lowercase(Locale.ROOT).trim()

        // Layer 1: Format Validation
        validateFormat(normalized)?.let { return it }

        val warnings = mutableListOf<ModerationWarning>()

        // Layer 2: Privacy Detection
        checkPrivacy(normalized)?.let { warnings.add(it) }

        // Layer 3: Safety & Reserved Names
        checkSafety(normalized)?.let { warnings.add(it) }

        // Layer 4: Emotional Atmosphere
        checkAtmosphere(normalized)?.let { warnings.add(it) }

        return UsernameValidationResult(
            isValid = warnings.isEmpty(),
            reason = warnings.firstOrNull()?.reason,
            warnings = warnings,
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

    private fun checkPrivacy(username: String): ModerationWarning? {
        // Simple Email Regex
        if (username.contains("@") || username.contains(".com") || username.contains(".net")) {
            return ModerationWarning(
                ValidationReason.EMAIL_ADDRESS,
                "This name looks like an email. Try something more anonymous."
            )
        }

        // Simple Phone Number Heuristic (sequences of numbers)
        val digitSequence = username.filter { it.isDigit() }
        if (digitSequence.length >= 7) {
            return ModerationWarning(
                ValidationReason.PHONE_NUMBER,
                "This name contains too many digits. Avoid using phone numbers."
            )
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
