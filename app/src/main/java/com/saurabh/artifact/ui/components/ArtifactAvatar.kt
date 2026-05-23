package com.saurabh.artifact.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.AvatarConfig

@Composable
fun ArtifactAvatar(
    config: AvatarConfig,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isStatic: Boolean = false
) {
    if (config.theme == "CARTOON") {
        CartoonAvatar(config = config, modifier = modifier, size = size)
    } else {
        AuricAvatar(seed = config.seed, modifier = modifier, size = size, isStatic = isStatic)
    }
}
