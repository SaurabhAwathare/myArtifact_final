package com.saurabh.artifact.ui.theme

/**
 * Centralized Z-index tokens to manage UI layering consistency.
 */
object ZIndexTokens {
    /** The base application content (Feed, Profile, etc.) */
    const val NAV_GRAPH = 0f
    
    /** Floating ambient components like MiniPlayer, MiniRecorder, and UploadBar */
    const val MINI_OVERLAYS = 100f
    
    /** Modal sheets and popups (e.g., ReportSheet, AdvancedControls) */
    const val MODAL_OVERLAYS = 2000f
    
    /** Full-screen cinematic experiences like the ImmersivePlayer */
    const val FULL_SCREEN_OVERLAYS = 1000f
}
