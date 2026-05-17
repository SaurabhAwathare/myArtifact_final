package com.saurabh.artifact.presentation.avatar.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset

data class AvatarConfig(
    val faceId: String,
    val hairId: String,
    val eyesId: String,
    val mouthId: String,
    val skinTone: Color,
    val hairColor: Color,
    val emotion: EmotionState = EmotionState.CALM
)

enum class EmotionState {
    CALM,
    THOUGHTFUL,
    HAPPY,
    REFLECTIVE
}

data class AvatarLayer(
    val resourceId: Int,
    val tint: Color? = null,
    val zIndex: Int,
    val offset: Offset = Offset.Zero
)
