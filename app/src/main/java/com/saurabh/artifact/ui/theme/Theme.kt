package com.saurabh.artifact.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.saurabh.artifact.startup.StartupStage

/**
 * Custom Semantic Color System for Artifact.
 * Extends beyond standard Material3 roles for emotional depth.
 */
@Immutable
data class ArtifactColorScheme(
    val surfaceLoom: Color,
    val surfaceHearth: Color,
    val surfaceGlow: Color,
    val onSurfaceMain: Color,
    val onSurfaceMuted: Color,
    val onSurfaceAura: Color,
    val waveformActive: Color,
    val waveformInactive: Color,
    val softError: Color
)

val LocalArtifactColors = staticCompositionLocalOf {
    ArtifactColorScheme(
        surfaceLoom = Obsidian950,
        surfaceHearth = Obsidian800,
        surfaceGlow = SurfaceGlow,
        onSurfaceMain = OnSurfaceMain,
        onSurfaceMuted = OnSurfaceMuted,
        onSurfaceAura = OnSurfaceAura,
        waveformActive = GoldAura500,
        waveformInactive = Obsidian700,
        softError = SoftError
    )
}

val LocalStartupStage = staticCompositionLocalOf { StartupStage.ARRIVAL }

/**
 * Provides a stable reference to the stability state to prevent tree-wide invalidations.
 */
val LocalIsStable = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

private val DarkColorScheme = darkColorScheme(
    primary = GoldAura500,
    onPrimary = Obsidian950,
    secondary = DeepMeditation,
    onSecondary = ReflectionWhite,
    tertiary = TrustMoss,
    background = Obsidian950, // OLED Optimized
    surface = Obsidian800,
    onBackground = ReflectionWhite,
    onSurface = ReflectionWhite,
    onSurfaceVariant = MistGray,
    error = SoftError,
    outline = Obsidian700
)

@Composable
fun ArtifactTheme(
    content: @Composable () -> Unit
) {
    // Artifact is a permanent dark-mode emotional experience.
    val colorScheme = DarkColorScheme
    
    val artifactColors = ArtifactColorScheme(
        surfaceLoom = Obsidian950,
        surfaceHearth = Obsidian800,
        surfaceGlow = SurfaceGlow,
        onSurfaceMain = OnSurfaceMain,
        onSurfaceMuted = OnSurfaceMuted,
        onSurfaceAura = OnSurfaceAura,
        waveformActive = GoldAura500,
        waveformInactive = Obsidian700,
        softError = SoftError
    )

    // Typography used by Material components.
    // We keep this stable (SafeTypography) to prevent Material component invalidations.
    // High-level screens will use ArtifactTheme.typography for localized upgrades.
    val typography = SafeTypography

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            // Always use light status bar icons for dark background
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    CompositionLocalProvider(
        LocalArtifactColors provides artifactColors,
        LocalEmotionalTokens provides EmotionalTokens()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}

/**
 * Accessor for the Artifact custom color scheme.
 */
object ArtifactTheme {
    val colors: ArtifactColorScheme
        @Composable
        get() = LocalArtifactColors.current
    
    val emotional: EmotionalTokens
        @Composable
        get() = LocalEmotionalTokens.current

    val typography: Typography
        @Composable
        get() = if (LocalStartupStage.current == StartupStage.STABLE) ArtifactTypography else SafeTypography

    val stage: StartupStage
        @Composable
        get() = LocalStartupStage.current

    val isStable: Boolean
        @Composable
        get() = LocalStartupStage.current == StartupStage.STABLE
}
