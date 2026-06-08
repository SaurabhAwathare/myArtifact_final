package com.saurabh.artifact.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Artifact Spacing System
 * 
 * Based on an 8dp grid for consistent visual rhythm and "breathing room".
 */
object Spacing {
    /** 4dp - Minimal gaps (e.g., icon + text) */
    val ExtraSmall = 4.dp
    
    /** 8dp - Tight grouping, internal list padding */
    val Small = 8.dp
    
    /** 16dp - Default padding for components, standard gaps */
    val Medium = 16.dp
    
    /** 24dp - Content section separation, internal card padding */
    val Large = 24.dp
    
    /** 32dp - Outer screen margins, major layout breaks */
    val ExtraLarge = 32.dp
}
