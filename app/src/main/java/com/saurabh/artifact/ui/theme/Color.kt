package com.saurabh.artifact.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Artifact "Golden Aura & Obsidian" Color System
 * 
 * Centered around "The Hearth" - a warm, deep atmosphere that creates emotional safety.
 * Refined from "Ember/Fire" to "Golden/Candlelight" for deeper calm.
 */

// --- 1. Primitive Palette ---

// The Void (Obsidian Layers - OLED Optimized)
val Obsidian950 = Color(0xFF0F0F0F) // Pure dark charcoal (Root/Loom)
val Obsidian900 = Color(0xFF121212) // Soft Atmosphere
val Obsidian800 = Color(0xFF181818) // Warm Surface (Base for Artifact Cards / Hearth)
val Obsidian700 = Color(0xFF222222) // Lighter Surface (Used for inactive waveforms / outlines)

// The Aura (Primary Accent - Golden Light)
val GoldAura500 = Color(0xFFFFB84D) // Core Presence
val GoldAura400 = Color(0xFFFFC76A) // Soft Illumination

// Emotional Secondary
val ReflectionWhite = Color(0xFFFAF7F2) // Bone white (Primary text)
val MistGray = Color(0xFF948F89)        // Reflective metadata (Secondary text)
val TrustMoss = Color(0xFF7A8574)       // Muted safety
val DeepMeditation = Color(0xFF3A3442)  // Atmospheric depth

// --- 2. Semantic Operational Tokens (Approved Stage 2.2 / Stage 3.1) ---

val RecordingActive = Color(0xFFE53935)
val RecordingPaused = RecordingActive.copy(alpha = 0.38f)
val RecordingPausedSolid = Color(0xFF601F1D) // Verified solid-color equivalent against Obsidian950 (#0F0F0F)
val ActionDestructive = Color(0xFFFF456A)
val StatusError = Color(0xFFD96B5F)
val StatusWarning = Color(0xFFFFB84D)

// Backward compatibility alias for existing usages
val SoftError = StatusError

// --- 3. Semantic Role Mapping ---

val SurfaceGlow = GoldAura400.copy(alpha = 0.1f)

val OnSurfaceMain = ReflectionWhite
val OnSurfaceMuted = MistGray
val OnSurfaceAura = GoldAura400

val WaveformActive = GoldAura500
val WaveformInactive = Obsidian700

// --- 4. Legacy Support / Aliases ---
val EmberGlow = GoldAura400
val MossSafe = TrustMoss
