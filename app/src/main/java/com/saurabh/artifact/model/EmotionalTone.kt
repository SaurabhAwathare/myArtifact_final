package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
enum class EmotionalTone {
    HEAVY,
    GENTLE,
    REFLECTIVE,
    STIMULATING,
    HOPEFUL,
    CALM;

    companion object {
        fun fromString(value: String): EmotionalTone {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: REFLECTIVE
        }
    }
}
