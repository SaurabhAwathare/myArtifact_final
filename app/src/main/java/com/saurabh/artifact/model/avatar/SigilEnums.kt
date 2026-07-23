package com.saurabh.artifact.model.avatar

import kotlinx.serialization.Serializable

/**
 * Abstract color palettes for Sigils.
 * These are collections of colors that harmonize together, rather than single choices.
 */
@Serializable
enum class SigilPalette {
    AURORA,    // Blues, Purples, Teals
    EMBER,     // Reds, Oranges, Golds
    FOREST,    // Greens, Browns, Creams
    MONO,      // Grays, Blacks, Whites
    CELESTIAL  // Deep Blues, Silvers, Indigo
}

/**
 * Visual variants that define the "presence" of the Sigil.
 */
@Serializable
enum class SigilVariant {
    LIGHT,     // High contrast on dark backgrounds
    DARK,      // High contrast on light backgrounds
    GHOST      // Semi-transparent, ethereal appearance
}

/**
 * Artistic style for the geometric primitives.
 */
@Serializable
enum class SigilStyle {
    OUTLINE,   // Clean strokes
    FILLED,    // Solid shapes
    MIXED      // A combination of strokes and fills
}
