package com.saurabh.artifact.domain.review

/**
 * Represents the authoritative backend state of a user's engagement with an artifact.
 */
enum class EngagementState {
    LOCKED,
    UNLOCKED,
    UNKNOWN;

    companion object {
        fun fromString(value: String?): EngagementState {
            return when (value?.uppercase()) {
                "LOCKED" -> LOCKED
                "UNLOCKED" -> UNLOCKED
                else -> UNKNOWN
            }
        }
    }
}
