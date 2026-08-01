package com.saurabh.artifact.service

import com.saurabh.artifact.model.PiiType
import com.saurabh.artifact.model.TranscriptSegment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles PII removal (numbers, dates, names, locations) from recording summaries
 * before AI egress. Uses [SensitiveInfoScanner] for detection.
 */
@Singleton
class ContextScrubber @Inject constructor(
    private val scanner: SensitiveInfoScanner
) {

    /**
     * Scrubs a text summary of PII.
     */
    suspend fun scrub(text: String): String {
        if (text.isBlank()) return ""

        // Wrap the text in a single segment for scanning
        val segments = listOf(TranscriptSegment(id = "scrub_target", text = text, startMs = 0, endMs = 0))
        val flagged = scanner.scan(segments)

        if (flagged.isEmpty()) return text

        var scrubbedText = text
        // Sort by start position descending to avoid index shifts during replacement
        // Note: SensitiveInfoScanner doesn't currently provide character offsets for all types,
        // so we'll rely on string replacement for now, which is safer given the "Smallest Safe Fix" rule.
        flagged.forEach { flag ->
            // Use the original text found by the scanner if available
            val target = flag.originalText
            if (target.isNotEmpty() && scrubbedText.contains(target)) {
                val placeholder = when (flag.type) {
                    PiiType.NAME -> "[NAME]"
                    PiiType.EMAIL -> "[EMAIL]"
                    PiiType.PHONE -> "[PHONE]"
                    PiiType.LOCATION -> "[LOCATION]"
                    PiiType.ID_NUMBER -> "[ID]"
                    PiiType.OTHER -> "[REDACTED]"
                }
                scrubbedText = scrubbedText.replace(target, placeholder)
            }
        }

        // Final safety net: scrub anything that looks like a number > 3 digits (likely dates, codes, or addresses)
        scrubbedText = scrubbedText.replace(Regex("\\b\\d{4,}\\b"), "[NUMBER]")

        return scrubbedText
    }
}
