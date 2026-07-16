package com.saurabh.artifact.ui.util

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Authoritative constants for global bottom overlay heights.
 * These represent the total occupied layout space of each component.
 */
object BottomOverlayConstants {
    /** Mini Player height: 88dp */
    val MINI_PLAYER_HEIGHT = 88.dp
    
    /** Mini Recorder occupied height: 64dp (content) + 16dp (padding) + 16dp (padding) = 96dp */
    val MINI_RECORDER_OCCUPIED_HEIGHT = 96.dp
    
    /** Spacing between Mini Player and Mini Recorder when both are visible */
    val OVERLAY_SPACING = 8.dp
}

/**
 * CompositionLocal providing the current total height of active bottom overlays.
 * Used to adjust the positioning of transient feedback (Snackbars) and other 
 * bottom-aligned UI elements like FABs.
 */
val LocalBottomOverlayOffset = compositionLocalOf<Dp> { 0.dp }
