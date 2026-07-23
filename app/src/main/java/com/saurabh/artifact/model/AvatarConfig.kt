package com.saurabh.artifact.model

import com.saurabh.artifact.model.avatar.*
import kotlinx.serialization.Serializable

/**
 * Represents the configuration for a unique, non-human visual identity (Sigil).
 * The visual form is deterministically generated from the [seed].
 * Users can only apply minor [refinements] to how that identity is presented.
 */
@Serializable
data class SigilConfig(
    val seed: String = "",
    val version: Int = 3,
    val palette: SigilPalette = SigilPalette.AURORA,
    val variant: SigilVariant = SigilVariant.LIGHT,
    val style: SigilStyle = SigilStyle.OUTLINE,
    val weight: Float = 2.0f
)
