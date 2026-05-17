package com.saurabh.artifact.audio.analysis

import java.util.concurrent.TimeUnit

/**
 * Descriptive patterns of emotional energy during a session.
 */
enum class EnergyPattern {
    STEADY,     // Consistent presence
    EXPANDING,  // Growing energy/intensity
    SETTLING,   // Moving toward quiet reflection
    FLUCTUATING, // Wave-like intensity
    UNCLEAR     // Not enough data
}

/**
 * A lightweight, privacy-safe capture of a single session's "shape".
 */
data class EmotionalSnapshot(
    val timestamp: Long,
    val themes: List<String>,
    val maxDepthReached: Int,
    val energyPattern: EnergyPattern,
    val durationSeconds: Long
)

/**
 * A soft, longitudinal insight derived from multiple sessions.
 */
data class ProgressInsight(
    val summary: String,
    val confidence: InsightConfidence,
    val detectedTrend: TrendType
)

enum class InsightConfidence { LOW, MEDIUM, HIGH }
enum class TrendType { DEPTH, CONSISTENCY, THEME_SHIFT }

/**
 * Production-ready engine for detecting soft behavioral shifts over time.
 * Operates on-device and focuses on observations rather than conclusions.
 */
object ProgressAnalyzer {

    private const val MIN_SESSIONS_FOR_INSIGHT = 4 // Increased for better stability
    private const val LOOKBACK_WINDOW_DAYS = 14L

    fun analyze(snapshots: List<EmotionalSnapshot>): ProgressInsight? {
        val now = System.currentTimeMillis()
        val recent = snapshots
            .filter { it.timestamp > now - TimeUnit.DAYS.toMillis(LOOKBACK_WINDOW_DAYS) }
            .filter { it.durationSeconds > 10 } // Filter out very short "test" sessions
            .sortedBy { it.timestamp }

        if (recent.size < MIN_SESSIONS_FOR_INSIGHT) return null

        // Priority-based selection of insights with confidence checks
        val insights = listOfNotNull(
            analyzeDepthShift(recent),
            analyzeThemeEvolution(recent),
            analyzeConsistency(recent)
        ).filter { it.confidence != InsightConfidence.LOW }
         .sortedByDescending { it.confidence }

        return insights.firstOrNull()
    }

    private fun analyzeDepthShift(recent: List<EmotionalSnapshot>): ProgressInsight? {
        val midPoint = recent.size / 2
        val firstHalf = recent.take(midPoint)
        val secondHalf = recent.takeLast(recent.size - midPoint)
        
        if (firstHalf.isEmpty() || secondHalf.isEmpty()) return null

        val avgDepth1 = firstHalf.map { it.maxDepthReached }.average()
        val avgDepth2 = secondHalf.map { it.maxDepthReached }.average()

        val diff = avgDepth2 - avgDepth1
        return when {
            diff >= 0.8 -> ProgressInsight(
                summary = "Some of your recent reflections seem to be reaching a bit deeper.",
                confidence = InsightConfidence.HIGH,
                detectedTrend = TrendType.DEPTH
            )
            diff >= 0.4 -> ProgressInsight(
                summary = "You might notice yourself exploring topics with a bit more depth lately.",
                confidence = InsightConfidence.MEDIUM,
                detectedTrend = TrendType.DEPTH
            )
            else -> null
        }
    }

    private fun analyzeConsistency(recent: List<EmotionalSnapshot>): ProgressInsight? {
        val energyPatterns = recent.map { it.energyPattern }.filter { it != EnergyPattern.UNCLEAR }
        if (energyPatterns.isEmpty()) return null

        val counts = energyPatterns.groupingBy { it }.eachCount()
        val mostFrequent = counts.maxByOrNull { it.value } ?: return null
        val frequencyRatio = mostFrequent.value.toFloat() / recent.size

        return if (frequencyRatio >= 0.6f) {
            val patternDesc = when(mostFrequent.key) {
                EnergyPattern.SETTLING -> "a settling, reflective energy"
                EnergyPattern.STEADY -> "a steady, grounded presence"
                EnergyPattern.EXPANDING -> "a growing sense of momentum"
                EnergyPattern.FLUCTUATING -> "a dynamic, wave-like flow"
                else -> return null
            }
            ProgressInsight(
                summary = "There has been $patternDesc in your recent reflections.",
                confidence = if (frequencyRatio >= 0.8f) InsightConfidence.HIGH else InsightConfidence.MEDIUM,
                detectedTrend = TrendType.CONSISTENCY
            )
        } else {
            null
        }
    }

    private fun analyzeThemeEvolution(recent: List<EmotionalSnapshot>): ProgressInsight? {
        val allThemes = recent.flatMap { it.themes }
        if (allThemes.isEmpty()) return null

        val themeCounts = allThemes.groupingBy { it }.eachCount()
        val recurrentThemes = themeCounts.filter { it.value >= 3 }.keys.toList()
        
        return if (recurrentThemes.isNotEmpty()) {
            val themeStr = recurrentThemes.take(2).joinToString(" and ")
            ProgressInsight(
                summary = "You've been returning to themes of $themeStr in your thoughts lately.",
                confidence = if (recurrentThemes.size >= 2) InsightConfidence.HIGH else InsightConfidence.MEDIUM,
                detectedTrend = TrendType.THEME_SHIFT
            )
        } else {
            null
        }
    }

    /**
     * Helper to map SummaryGenerator output to Progress Model.
     */
    fun mapPattern(trend: String): EnergyPattern {
        val lowerTrend = trend.lowercase()
        return when {
            lowerTrend.contains("steady") || lowerTrend.contains("consistent") -> EnergyPattern.STEADY
            lowerTrend.contains("rising") || lowerTrend.contains("expanding") || lowerTrend.contains("momentum") -> EnergyPattern.EXPANDING
            lowerTrend.contains("falling") || lowerTrend.contains("softer") || lowerTrend.contains("settling") -> EnergyPattern.SETTLING
            lowerTrend.contains("fluctuating") || lowerTrend.contains("waves") || lowerTrend.contains("varying") -> EnergyPattern.FLUCTUATING
            else -> EnergyPattern.UNCLEAR
        }
    }
}
