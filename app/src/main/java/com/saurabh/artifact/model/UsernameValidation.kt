package com.saurabh.artifact.model

/**
 * Represents the various reasons a username might be flagged or rejected.
 * Structured to provide "protective guidance" rather than harsh rejection.
 */
enum class ValidationReason {
    // Format
    TOO_SHORT,
    TOO_LONG,
    INVALID_CHARACTERS,

    // Privacy
    PHONE_NUMBER,
    EMAIL_ADDRESS,
    REAL_NAME,
    LOCATION_REFERENCE,
    MOTIF_REUSE,
    CONTACT_PIVOT,
    INTRODUCTION_PATTERN,
    TRIANGULATION_RISK,

    // Safety
    HARASSMENT,
    SEXUAL_CONTENT,
    HATEFUL_LANGUAGE,
    RESERVED_NAME,

    // Atmosphere
    AGGRESSIVE_TONE,
    OVERLY_NEGATIVE,

    // Availability
    ALREADY_TAKEN,
    CHECK_FAILED
}

/**
 * Detail for moderation warnings to provide context and guidance.
 */
data class ModerationWarning(
    val reason: ValidationReason,
    val message: String,
    val suggestion: String? = null
)

/**
 * Result of the multi-layer validation pipeline.
 */
data class UsernameValidationResult(
    val isValid: Boolean,
    val reason: ValidationReason? = null,
    val warnings: List<ModerationWarning> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val riskScore: Float = 0f
)

/**
 * UI State for the username selection screen.
 */
data class UsernameUiState(
    val username: String = "",
    val isValidating: Boolean = false,
    val isAvailable: Boolean? = null,
    val validationResult: UsernameValidationResult? = null,
    val suggestions: List<String> = emptyList(),
    val isProcessing: Boolean = false
)
