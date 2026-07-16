package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.ui.util.LocalBottomOverlayOffset

/**
 * A specialized SnackbarHost that automatically respects the global bottom overlay offset.
 * This ensures that Snackbars are never obscured by the Mini Player or Mini Recorder.
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val bottomOffset = LocalBottomOverlayOffset.current
    
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.padding(bottom = bottomOffset)
    )
}
