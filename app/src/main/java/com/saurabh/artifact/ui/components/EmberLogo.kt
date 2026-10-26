@file:Suppress("SameParameterValue")

package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.saurabh.artifact.ui.theme.ArtifactTheme

/**
 * Unified AuraLogo delegating to canonical HumanALogo.
 */
@Composable
fun AuraLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    isPulsing: Boolean = true
) = HumanALogo(modifier = modifier, size = size, isPulsing = isPulsing)

/** Legacy alias for smooth transition */
@Composable
fun EmberLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    isPulsing: Boolean = true
) = HumanALogo(modifier = modifier, size = size, isPulsing = isPulsing)

@Preview(showBackground = true, backgroundColor = 0xFF050505)
@Composable
fun AuraLogoPreview() {
    ArtifactTheme {
        Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            AuraLogo()
        }
    }
}
