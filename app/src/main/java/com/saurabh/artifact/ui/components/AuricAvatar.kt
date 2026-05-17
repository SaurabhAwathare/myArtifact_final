package com.saurabh.artifact.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.theme.EmberGlow
import com.saurabh.artifact.ui.theme.MossSafe
import com.saurabh.artifact.ui.theme.DeepViolet

/**
 * AuricAvatar - A generative gradient avatar.
 * Replaces photos with "emotional auras" derived from user data/seed.
 */
@Composable
fun AuricAvatar(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    // Generate stable colors based on the seed
    val colors = remember(seed) {
        val hash = seed.hashCode()
        listOf(
            Color((hash and 0xFFFFFF) or 0xFF000000.toInt()).copy(alpha = 0.8f),
            if (hash % 2 == 0) EmberGlow else MossSafe,
            DeepViolet.copy(alpha = 0.6f)
        ).shuffled()
    }

    Canvas(modifier = modifier.size(size)) {
        drawCircle(
            brush = Brush.linearGradient(
                colors = colors
            )
        )
        
        // Soft inner glow to give volume
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.2f),
                0.8f to Color.Transparent,
            ),
            radius = size.toPx() * 0.4f
        )
    }
}
