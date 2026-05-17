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
val Obsidian950 = Color(0xFF0F0F0F) // Pure dark charcoal
val Obsidian900 = Color(0xFF121212) // Soft Atmosphere
val Obsidian800 = Color(0xFF181818) // Warm Surface
val Obsidian700 = Color(0xFF222222) // Lighter Surface

// The Aura (Primary Accent - Golden Light)
val GoldAura500 = Color(0xFFFFB84D) // Core Presence
val GoldAura400 = Color(0xFFFFC76A) // Soft Illumination
val GoldAura600 = Color(0xFFE0A33A) // Grounded Value

// Emotional Secondary
val EmotionalRed = Color(0xFFD96B5F) // Emotional Red
val ReflectionWhite = Color(0xFFFAF7F2) // Bone white
val MistGray = Color(0xFF948F89)        // Reflective metadata
val TrustMoss = Color(0xFF7A8574)       // Muted safety
val DeepMeditation = Color(0xFF3A3442)  // Atmospheric depth

// Functional
val SoftError = Color(0xFFD96B5F) // Using Emotional Red for errors too, as per design vibe

// --- 2. Semantic Role Mapping ---

val SurfaceLoom = Obsidian950
val SurfaceHearth = Obsidian800
val SurfaceGlow = GoldAura400.copy(alpha = 0.1f)

val OnSurfaceMain = ReflectionWhite
val OnSurfaceMuted = MistGray
val OnSurfaceAura = GoldAura400

// --- 3. Legacy Support / Primitive Accessors ---
// Aliases for smooth transition of existing components
val DarkAtmosphere = Obsidian900
val DarkSurface = Obsidian800
val DarkPresence = GoldAura400
val DarkReflection = ReflectionWhite
val DarkWhisper = MistGray

val EmberGlow = GoldAura400
val EmberHeart = GoldAura500
val BoneWhite = ReflectionWhite
val MutedText = MistGray
val MossSafe = TrustMoss
val DeepViolet = DeepMeditation
