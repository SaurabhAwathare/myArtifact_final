package com.saurabh.artifact.ui.components.motion

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Artifact "Calm Motion" tokens.
 * 
 * Enforces a "breathing" and "suspended" aesthetic.
 * Transitions should feel like air or water, never mechanical.
 */
object MotionTokens {
    const val DURATION_SHORT = 300
    const val DURATION_MEDIUM = 600
    const val DURATION_LONG = 900

    // Bessel-inspired "Calm Easing" for natural, weighted movement.
    val CalmEasing = CubicBezierEasing(0.48f, 0.15f, 0.25f, 0.99f)
    
    val DefaultEasing = CalmEasing
}

/**
 * A reusable wrapper that provides tactile scale feedback on press.
 * Uses a soft spring for "organic" feel.
 */
@Composable
fun PressableScale(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scaleDownTo: Float = 0.96f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDownTo else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pressableScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick
                    )
                } else Modifier
            )
    ) {
        content()
    }
}

/**
 * Animates the appearance of its content with a subtle fade and soft vertical float.
 */
@Composable
fun FadeInContent(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    delay: Int = 0,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = MotionTokens.DURATION_MEDIUM,
                delayMillis = delay,
                easing = MotionTokens.CalmEasing
            )
        ) + slideInVertically(
            initialOffsetY = { it / 8 },
            animationSpec = tween(
                durationMillis = MotionTokens.DURATION_MEDIUM,
                delayMillis = delay,
                easing = MotionTokens.CalmEasing
            )
        ),
        exit = fadeOut(
            animationSpec = tween(
                durationMillis = MotionTokens.DURATION_SHORT,
                easing = MotionTokens.CalmEasing
            )
        ),
        modifier = modifier
    ) {
        content()
    }
}
