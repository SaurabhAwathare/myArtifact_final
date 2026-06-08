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
    val seed: String = "",
    val version: Int = 2,
    val theme: String = "AURIC", // AURIC, CARTOON
    val faceShape: FaceShape = FaceShape.ROUND,
    val hairType: HairType = HairType.SHORT,
    val eyeType: EyeType = EyeType.NEUTRAL,
    val mouthType: MouthType = MouthType.SMILE,
    val accessoryType: AccessoryType = AccessoryType.NONE,
    val skinColor: String = "#FFDBAC",
    val hairColor: String = "#4A2C2C",
    val outfitColor: String = "#4A90E2"
)
