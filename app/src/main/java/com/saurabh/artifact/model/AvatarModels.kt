package com.saurabh.artifact.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
enum class AvatarExpression {
    CALM, 
    THOUGHTFUL, 
    REFLECTIVE, 
    HOPEFUL, 
    TIRED, 
    PEACEFUL, 
    SOFT_SMILE,
    VULNERABLE,
    DISTANT,
    STEADY
}

@Serializable
data class AvatarConfig(
    val headId: String = "head_01",
    val hairId: String = "hair_01",
    val eyeId: String = "eye_calm",
    val mouthId: String = "mouth_neutral",
    val outfitId: String = "outfit_hoodie",
    val skinTone: Long = 0xFFF5E0D3,
    val hairColor: Long = 0xFF2B1B17,
    val outfitColor: Long = 0xFF4A5568,
    val expression: AvatarExpression = AvatarExpression.CALM,
    val ambientGlow: Long = 0xFFE0C3FC,
    val glowEnabled: Boolean = true
) {
    fun getSkinToneColor() = Color(skinTone)
    fun getHairColor() = Color(hairColor)
    fun getOutfitColor() = Color(outfitColor)
    fun getAmbientGlowColor() = Color(ambientGlow)
}

data class AvatarCategory(
    val id: String,
    val label: String,
    val icon: Int? = null // Resource ID for icon
)

data class AvatarOption(
    val id: String,
    val categoryId: String,
    val preview: String? = null // Could be a path or a simple identifier
)
