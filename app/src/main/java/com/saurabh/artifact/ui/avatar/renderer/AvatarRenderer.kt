package com.saurabh.artifact.ui.avatar.renderer

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.saurabh.artifact.model.AvatarConfig

/**
 * Interface for rendering different types of avatars.
 * Decouples drawing logic from Compose UI state management.
 */
interface AvatarRenderer {
    fun DrawScope.render(config: AvatarConfig, animationState: AvatarAnimationState)
}

/**
 * Encapsulates dynamic animation values used during rendering.
 */
data class AvatarAnimationState(
    val pulse: Float = 1f,
    val rotation: Float = 0f
)

/**
 * Factory to provide the appropriate renderer based on the config theme.
 */
object AvatarRendererFactory {
    private val auricRenderer = AuricRenderer()
    private val cartoonRenderer = CartoonRenderer()

    fun getRenderer(theme: String): AvatarRenderer {
        return when (theme.uppercase()) {
            "CARTOON" -> cartoonRenderer
            else -> auricRenderer
        }
    }
}
