package com.saurabh.artifact.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.AvatarConfig
import com.saurabh.artifact.ui.avatar.renderer.AvatarAnimationState
import com.saurabh.artifact.ui.avatar.renderer.CartoonRenderer

/**
 * Simplified CartoonAvatar - Now a wrapper around CartoonRenderer.
 */
@Composable
fun CartoonAvatar(
    config: AvatarConfig,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val renderer = remember { CartoonRenderer() }

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            with(renderer) {
                render(config, AvatarAnimationState())
            }
        }
    }
}
