package com.saurabh.artifact.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Artifact Shape System
 * 
 * Focused on:
 * 1. Softness: Large corner radii to feel approachable.
 * 2. Modernity: Clean, rounded surfaces.
 * 3. Consistency: Standard tokens for different component scales.
 *
 * Aligned in Stage 3.1:
 * - small: 12dp (tags, emotion markers)
 * - medium: 16dp (shared card shape token aligned with approved 16dp specification)
 * - large: 28dp (modal sheets, dialogs, floating system surfaces)
 */
val AppShapes = Shapes(
    // Tiny elements like tags, emotion markers
    small = RoundedCornerShape(12.dp),
    
    // Core containers like cards and inner sheets (16dp shared card standard)
    medium = RoundedCornerShape(16.dp),
    
    // Large surfaces like dialogs and bottom sheets
    large = RoundedCornerShape(28.dp),
)

/**
 * Shared Card Shape token (16dp).
 */
val CardShape = RoundedCornerShape(16.dp)

/**
 * Modal sheet and dialog shape token (28dp).
 */
val ModalShape = RoundedCornerShape(28.dp)
