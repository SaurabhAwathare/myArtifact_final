package com.saurabh.artifact.service

import com.saurabh.artifact.model.TranscriptSegment
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class PrivacyAnalysisEngine @Inject constructor(
    private val scanner: SensitiveInfoScanner
) {

    suspend fun analyze(transcriptPath: String? = null): PrivacyAnalysisResult {
        delay(500.milliseconds) // Keep subtle UI feedback delay

        val transcript = if (transcriptPath != null) {
            val file = File(transcriptPath)
            if (file.exists()) file.readText() else ""
        } else {
            // Fallback for demo if no transcript exists yet
            "My name is Rahul from Pune, and I wanted to talk about my day at work."
        }
        
        val segments = listOf(
            TranscriptSegment(
                id = "demo",
                text = transcript,
                startMs = 0,
                endMs = 60000,
                confidence = 1.0f
            )
        )
        
        val flagged = scanner.scan(segments)
        val risks = flagged.map { "Potential ${it.type} detected: ${it.originalText}" }
        
        return PrivacyAnalysisResult(
            transcript = transcript,
            detectedRisks = risks,
            safetyLevel = if (flagged.isNotEmpty()) SafetyLevel.MEDIUM else SafetyLevel.LOW
        )
    }
}

data class PrivacyAnalysisResult(
    val transcript: String,
    val detectedRisks: List<String>,
    val safetyLevel: SafetyLevel
)
