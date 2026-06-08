package com.saurabh.artifact.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.ui.avatar.renderer.AvatarAnimationState
import com.saurabh.artifact.ui.avatar.renderer.AvatarRendererFactory

@Composable
fun ArtifactAvatar(
    config: AvatarConfig,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isStatic: Boolean = false
) {
    val renderer = remember(config.theme) {
        AvatarRendererFactory.getRenderer(config.theme)
    }

    // Common animation logic hosted here
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_animation")
    
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

    val animationState = if (isStatic) {
        AvatarAnimationState()
    } else {
        AvatarAnimationState(pulse = { pulse }, rotation = { rotation })
    }

    Box(modifier = modifier.size(size)) {
        renderer.Render(
            config = config,
            animationState = animationState,
            modifier = Modifier.fillMaxSize()
        )
    }
}