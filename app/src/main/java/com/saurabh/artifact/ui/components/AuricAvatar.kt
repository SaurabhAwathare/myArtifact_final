package com.saurabh.artifact.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.ui.avatar.renderer.AuricRenderer
import com.saurabh.artifact.ui.avatar.renderer.AvatarAnimationState

/**
 * Simplified AuricAvatar - Now a wrapper around AuricRenderer.
 */
@Composable
fun AuricAvatar(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isStatic: Boolean = false
) {
    val renderer = remember { AuricRenderer() }
    val config = remember(seed) { AvatarConfig(seed = seed, theme = "AURIC") }

    val infiniteTransition = rememberInfiniteTransition(label = "aura_animation")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val animationState = remember(isStatic) {
        if (isStatic) {
            AvatarAnimationState()
        } else {
            AvatarAnimationState(
                pulse = { pulse },
                rotation = { rotation }
            )
        }
    }

    Box(modifier = modifier.size(size)) {
        renderer.Render(
            config = config,
            animationState = animationState,
            modifier = Modifier.fillMaxSize()
        )
    }
}
