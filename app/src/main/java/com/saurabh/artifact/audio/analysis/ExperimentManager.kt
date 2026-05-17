package com.saurabh.artifact.audio.analysis

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.experimentDataStore by preferencesDataStore(name = "experiment_settings")

/**
 * Defines the parameters for system-wide behavioral tuning experiments.
 */
data class ExperimentConfig(
    val id: String = "v1_base",
    val timingVariant: TimingVariant = TimingVariant.CONTROL,
    val depthVariant: DepthVariant = DepthVariant.CONTROL,
    val suggestionVariant: SuggestionVariant = SuggestionVariant.CONTROL
)

enum class TimingVariant {
    CONTROL,    // 6s Stuck, 15s Closing
    PATIENT,    // 10s Stuck, 20s Closing
    ACTIVE      // 4s Stuck, 10s Closing
}

enum class DepthVariant {
    CONTROL,    // Neutral score transitions
    CHALLENGE,  // Slight upward bias (easier to hit DEEP)
    GENTLE      // Slight downward bias (stays in LIGHT longer)
}

enum class SuggestionVariant {
    CONTROL,    // Normal suggestions
    MINIMAL,    // Only "Closing" suggestions
    VERBOSE     // More frequent "Processing" nudges
}

/**
 * Manages local A/B test assignments and configuration.
 * Assignments are stable per device.
 */
@Singleton
class ExperimentManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun getConfig(): ExperimentConfig {
        val prefs = context.experimentDataStore.data.first()
        var variantId = prefs[VARIANT_KEY]

        if (variantId == null) {
            // Assign a random but stable variant on first run
            variantId = assignRandomVariant()
            context.experimentDataStore.edit { it[VARIANT_KEY] = variantId }
        }

        return parseConfig(variantId)
    }

    private fun assignRandomVariant(): String {
        val variants = listOf("A", "B", "C")
        return variants.random()
    }

    private fun parseConfig(variantId: String): ExperimentConfig {
        val config = when (variantId) {
            "B" -> ExperimentConfig(
                id = "v1_patient_gentle",
                timingVariant = TimingVariant.PATIENT,
                depthVariant = DepthVariant.GENTLE,
                suggestionVariant = SuggestionVariant.MINIMAL
            )
            "C" -> ExperimentConfig(
                id = "v1_active_challenge",
                timingVariant = TimingVariant.ACTIVE,
                depthVariant = DepthVariant.CHALLENGE,
                suggestionVariant = SuggestionVariant.VERBOSE
            )
            else -> ExperimentConfig() // CONTROL
        }
        Log.d("ExperimentManager", "Loaded config: ${config.id} (Timing: ${config.timingVariant}, Depth: ${config.depthVariant}, Suggestion: ${config.suggestionVariant})")
        return config
    }

    companion object {
        private val VARIANT_KEY = stringPreferencesKey("experiment_variant_id")
    }
}
