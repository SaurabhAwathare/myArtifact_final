package com.saurabh.artifact.service

import com.saurabh.artifact.model.PiiType
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.FlaggedSegment
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensitiveInfoScanner @Inject constructor() {

    private val phoneRegex = """(\+\d{1,2}\s?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}""".toRegex()
    private val emailRegex = """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""".toRegex()
    
    // Simple mock patterns for demonstration. In production, use ML Kit Entity Extraction or dedicated NLP.
    private val locationKeywords = listOf("street", "avenue", "road", "city", "town", "county", "state", "country")
    private val commonNames = listOf("Rahul", "Saurabh", "John", "Jane", "Alice", "Bob")

    fun scan(transcript: List<TranscriptSegment>): List<FlaggedSegment> {
        val flagged = mutableListOf<FlaggedSegment>()
        
        transcript.forEach { segment ->
            // Scan for Phone Numbers
            phoneRegex.findAll(segment.text).forEach { match ->
                flagged.add(createFlag(match, segment, PiiType.PHONE))
            }
            
            // Scan for Emails
            emailRegex.findAll(segment.text).forEach { match ->
                flagged.add(createFlag(match, segment, PiiType.EMAIL))
            }
            
            // Scan for Names (Mock)
            commonNames.forEach { name ->
                if (segment.text.contains(name, ignoreCase = true)) {
                    val index = segment.text.indexOf(name, ignoreCase = true)
                    flagged.add(
                        FlaggedSegment(
                            id = UUID.randomUUID().toString(),
                            type = PiiType.NAME,
                            startMs = segment.startMs,
                            endMs = segment.endMs,
                            originalText = segment.text.substring(index, index + name.length),
                            confidence = 0.8f,
                        )
                    )
                }
            }

            // Scan for Locations (Mock)
            locationKeywords.forEach { keyword ->
                if (segment.text.contains(keyword, ignoreCase = true)) {
                    // Logic to find surrounding words as potential location
                    flagged.add(
                        FlaggedSegment(
                            id = UUID.randomUUID().toString(),
                            type = PiiType.LOCATION,
                            startMs = segment.startMs,
                            endMs = segment.endMs,
                            originalText = "Potential Location: $keyword",
                            confidence = 0.5f
                        )
                    )
                }
            }
        }
        
        return flagged.distinctBy { it.originalText + it.startMs }
    }

    private fun createFlag(match: MatchResult, segment: TranscriptSegment, type: PiiType): FlaggedSegment {
        return FlaggedSegment(
            id = UUID.randomUUID().toString(),
            type = type,
            startMs = segment.startMs,
            endMs = segment.endMs,
            originalText = match.value,
            confidence = 1.0f
        )
    }
}
