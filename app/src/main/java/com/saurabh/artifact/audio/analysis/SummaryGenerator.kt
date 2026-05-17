package com.saurabh.artifact.audio.analysis

import com.saurabh.artifact.model.ReflectionQuestion
import kotlin.math.abs

/**
 * Structured summary of a recording session.
 */
data class SessionSummary(
    val flowObservation: String,
    val landingNote: String,
    val themes: List<String>,
    val confidence: SummaryConfidence = SummaryConfidence.MEDIUM,
    val isEvolvedInsight: Boolean = false
)

enum class SummaryConfidence {
    LOW, MEDIUM, HIGH
}

/**
 * Production-hardened generator for emotionally intelligent reflection summaries.
 * Focuses on patterns and observations rather than deterministic emotional labeling.
 */
object SummaryGenerator {

    fun generate(
        amplitudes: List<Float>,
        usedPrompts: List<ReflectionQuestion>,
        durationSeconds: Long
    ): SessionSummary {
        if (amplitudes.isEmpty()) {
            return SessionSummary(
                flowObservation = "A quiet moment of presence.",
                landingNote = "Sometimes the most meaningful reflections happen in the silence.",
                themes = emptyList(),
                confidence = SummaryConfidence.LOW
            )
        }

        val themes = extractThemes(usedPrompts)
        val flowAnalysis = analyzeFlow(amplitudes)
        val confidence = determineConfidence(amplitudes, durationSeconds)
        
        return SessionSummary(
            flowObservation = buildNarrative(flowAnalysis, confidence),
            landingNote = generateLanding(amplitudes, usedPrompts.lastOrNull()),
            themes = themes,
            confidence = confidence
        )
    }

    private fun extractThemes(prompts: List<ReflectionQuestion>): List<String> {
        // Use structured tags from the questions instead of raw text parsing
        return prompts.flatMap { it.tags }
            .distinct()
            .take(3)
            .ifEmpty { listOf("General Reflection") }
    }

    private data class FlowAnalysis(
        val energyTrend: Trend,
        val consistency: Float, // 0.0 to 1.0, 1.0 being very consistent speaking
        val silenceRatio: Float
    )

    private enum class Trend {
        RISING, FALLING, STEADY, FLUCTUATING
    }

    private fun analyzeFlow(amplitudes: List<Float>): FlowAnalysis {
        val chunks = amplitudes.chunked(maxOf(1, amplitudes.size / 3))
        val averages = chunks.map { it.average().toFloat() }
        
        val trend = when {
            averages.size < 2 -> Trend.STEADY
            averages.last() > averages.first() * 1.5f -> Trend.RISING
            averages.last() < averages.first() * 0.5f -> Trend.FALLING
            else -> Trend.STEADY
        }

        val mean = amplitudes.average().toFloat()
        val variance = amplitudes.map { abs(it - mean) }.average().toFloat()
        val consistency = (1f - (variance / (mean + 0.001f))).coerceIn(0f, 1f)
        
        val silenceCount = amplitudes.count { it < 0.01f }
        val silenceRatio = silenceCount.toFloat() / amplitudes.size

        return FlowAnalysis(trend, consistency, silenceRatio)
    }

    private fun determineConfidence(amplitudes: List<Float>, duration: Long): SummaryConfidence {
        return when {
            duration < 15 || amplitudes.size < 50 -> SummaryConfidence.LOW
            duration > 60 && amplitudes.count { it > 0.05f } > amplitudes.size / 2 -> SummaryConfidence.HIGH
            else -> SummaryConfidence.MEDIUM
        }
    }

    private fun buildNarrative(analysis: FlowAnalysis, confidence: SummaryConfidence): String {
        val intro = when (confidence) {
            SummaryConfidence.HIGH -> "It seemed like "
            SummaryConfidence.MEDIUM -> "There was a sense that "
            SummaryConfidence.LOW -> "It felt possibly as though "
        }

        val pattern = when (analysis.energyTrend) {
            Trend.RISING -> "your energy grew as you spoke, finding more momentum towards the end"
            Trend.FALLING -> "you started with intensity and gradually moved toward a softer, more reflective space"
            Trend.STEADY -> "you maintained a consistent, steady presence throughout the session"
            Trend.FLUCTUATING -> "your reflection moved through different waves of intensity"
        }

        val nuance = if (analysis.silenceRatio > 0.3f) {
            ", with meaningful pauses that allowed your thoughts to breathe."
        } else {
            "."
        }

        return "$intro$pattern$nuance"
    }

    private fun generateLanding(amplitudes: List<Float>, lastPrompt: ReflectionQuestion?): String {
        val lastEnergy = amplitudes.takeLast(amplitudes.size / 5).average()
        
        return when {
            lastEnergy < 0.05f -> "Ending in this quiet space suggests a deep sense of integration."
            (lastPrompt?.depthLevel ?: 0) >= 3 -> "You've moved through some deep territory today. Carrying this insight forward."
            else -> "A gentle close to your reflection. Keep this feeling with you."
        }
    }
}
