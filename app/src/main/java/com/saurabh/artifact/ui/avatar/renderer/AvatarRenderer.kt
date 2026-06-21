package com.saurabh.artifact.ui.avatar.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.saurabh.artifact.model.AvatarConfig

/**
 * Interface for rendering different types of avatars.
 * Decouples drawing logic from Compose UI state management.
 */
interface AvatarRenderer {
    @Composable
    fun Render(
        config: AvatarConfig,
        animationState: AvatarAnimationState,
        modifier: Modifier
    )
}

/**
 * Encapsulates dynamic animation values used during rendering.
 * Uses lambdas to defer state reads and avoid unnecessary recompositions.
 */
data class AvatarAnimationState(
    val pulse: () -> Float = { 1f },
    val rotation: () -> Float = { 0f }
)

/**
 * Factory to provide the appropriate renderer based on the config theme.
 */
object AvatarRendererFactory {
    private val cartoonRenderer = CartoonRenderer()

    fun getRenderer(theme: String): AvatarRenderer {
        // All avatars now use the CartoonRenderer regardless of legacy theme strings
        return cartoonRenderer
    }
}
