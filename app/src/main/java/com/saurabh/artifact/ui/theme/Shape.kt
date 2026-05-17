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
 */
val AppShapes = Shapes(
    // Tiny elements like tags, emotion markers
    small = RoundedCornerShape(12.dp),
    
    // Core containers like cards and inner sheets
    medium = RoundedCornerShape(20.dp),
    
    // Large surfaces like dialogs and bottom sheets
    large = RoundedCornerShape(28.dp)
)
