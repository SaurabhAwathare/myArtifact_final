package com.saurabh.artifact.model

import com.saurabh.artifact.model.avatar.*
import kotlinx.serialization.Serializable

/**
 * Represents the configuration for a procedural anonymous identity.
 * Versioning allows for evolving the rendering algorithm while maintaining
 * visual continuity for older artifacts.
 */
@Serializable
data class AvatarConfig(
    var seed: String = "",
    var version: Int = 1,
    var theme: String = "CARTOON", // AURIC, CARTOON
    var faceShape: FaceShape = FaceShape.ROUND,
    var hairType: HairType = HairType.SHORT,
    var eyeType: EyeType = EyeType.NEUTRAL,
    var mouthType: MouthType = MouthType.SMILE,
    var accessoryType: AccessoryType = AccessoryType.NONE,
    var skinColor: String = "#FFDBAC",
    var hairColor: String = "#4A2C2C",
    var outfitColor: String = "#4A90E2"
)
