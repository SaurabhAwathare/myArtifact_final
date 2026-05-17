package com.saurabh.artifact.audio.analysis

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.metricsDataStore by preferencesDataStore(name = "behavior_metrics")

/**
 * Anonymous, aggregated behavioral signals for system-wide tuning.
 * These metrics contain NO personal data, raw audio, or transcripts.
 */
@Serializable
data class BehaviorMetrics(
    val avgSessionDurationSeconds: Float = 0f,
    val avgSilenceBeforeResumeMs: Long = 0,
    val suggestionAcceptanceRate: Float = 0f,
    val depthTransitionDropOff: Map<Int, Float> = emptyMap(), // Depth level -> drop-off rate
    val avgPromptEngagementTime: Map<String, Float> = emptyMap(), // Prompt ID (anonymized/hashed) -> duration
    val silenceFrequencyPerMinute: Float = 0f
)

/**
 * A collector that aggregates session-level behavioral data locally before anonymized reporting.
 */
object BehaviorMetricsCollector {
    
    private val SESSION_LOGS_KEY = stringPreferencesKey("session_logs")
    private const val BATCH_SIZE = 5
    private val scope = CoroutineScope(Dispatchers.IO)

    @Serializable
    data class SessionStats(
        val durationSeconds: Long,
        val silencesBeforeResume: List<Long>,
        val suggestionsShown: Int,
        val suggestionsFollowed: Int,
        val depthReached: Int,
        val skippedQuestions: Int,
        val experimentId: String
    )

    fun recordSession(context: Context, stats: SessionStats) {
        scope.launch {
            val currentLogs = getPersistedLogs(context).toMutableList()
            currentLogs.add(stats)
            
            if (currentLogs.size >= BATCH_SIZE) {
                reportAggregatedMetrics(currentLogs)
                context.metricsDataStore.edit { it.remove(SESSION_LOGS_KEY) }
            } else {
                saveLogs(context, currentLogs)
            }
        }
    }

    private suspend fun getPersistedLogs(context: Context): List<SessionStats> {
        val prefs = context.metricsDataStore.data.first()
        val json = prefs[SESSION_LOGS_KEY] ?: return emptyList()
        return try {
            Json.decodeFromString(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun saveLogs(context: Context, logs: List<SessionStats>) {
        val json = Json.encodeToString(logs)
        context.metricsDataStore.edit { it[SESSION_LOGS_KEY] = json }
    }

    private fun reportAggregatedMetrics(sessionLogs: List<SessionStats>) {
        if (sessionLogs.isEmpty()) return

        // Calculate averages from sessionLogs
        // In a production app, this would be sent to a privacy-safe telemetry endpoint
        // as a single anonymized JSON payload.
        val aggregated = BehaviorMetrics(
            avgSessionDurationSeconds = sessionLogs.map { it.durationSeconds }.average().toFloat(),
            avgSilenceBeforeResumeMs = sessionLogs.flatMap { it.silencesBeforeResume }.average().toLong(),
            suggestionAcceptanceRate = if (sessionLogs.sumOf { it.suggestionsShown } > 0) {
                sessionLogs.sumOf { it.suggestionsFollowed }.toFloat() / sessionLogs.sumOf { it.suggestionsShown }
            } else 0f,
            silenceFrequencyPerMinute = sessionLogs.sumOf { it.silencesBeforeResume.size }.toFloat() / 
                    (sessionLogs.sumOf { it.durationSeconds } / 60f).coerceAtLeast(1f)
        )
        // Log for debug/architectural validation
        println("Aggregated Behavior Tuning Metrics: $aggregated")
    }
}
