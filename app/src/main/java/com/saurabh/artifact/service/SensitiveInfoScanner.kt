package com.saurabh.artifact.service

import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.util.SecureString
import com.saurabh.artifact.model.PiiType
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.model.FlaggedSegment
import com.saurabh.artifact.model.ValidationReason
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensitiveInfoScanner @Inject constructor(
    private val identityScout: IdentityScout,
    private val auth: com.google.firebase.auth.FirebaseAuth
) {

    private val phoneRegex = """(\+\d{1,2}\s?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}""".toRegex()
    private val emailRegex = """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""".toRegex()
    
    // Simple mock patterns for demonstration. In production, use ML Kit Entity Extraction or dedicated NLP.
    private val locationKeywords = listOf("street", "avenue", "road", "city", "town", "county", "state", "country")

    fun scan(transcript: List<TranscriptSegment>): List<FlaggedSegment> {
        val flagged = mutableListOf<FlaggedSegment>()
        val realName = auth.currentUser?.displayName?.let { SecureString.fromString(it) }
        val email = auth.currentUser?.email?.let { SecureString.fromString(it) }
        
        transcript.forEach { segment ->
            // 1. Scan for Phone Numbers
            phoneRegex.findAll(segment.text).forEach { match ->
                flagged.add(createFlag(match, segment, PiiType.PHONE))
            }
            
            // 2. Scan for Emails
            emailRegex.findAll(segment.text).forEach { match ->
                flagged.add(createFlag(match, segment, PiiType.EMAIL))
            }
            
            // 3. Scan for Locations (Mock)
            locationKeywords.forEach { keyword ->
                if (segment.text.contains(keyword, ignoreCase = true)) {
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

            // 4. Integrated IdentityScout Behavioral & Motif Scan
            val leaks = identityScout.detectLeaks(segment.text, realName, email)
            leaks.forEach { leak ->
                val piiType = when (leak.reason) {
                    ValidationReason.REAL_NAME, ValidationReason.MOTIF_REUSE -> PiiType.NAME
                    ValidationReason.EMAIL_ADDRESS -> PiiType.EMAIL
                    ValidationReason.PHONE_NUMBER -> PiiType.PHONE
                    ValidationReason.LOCATION_REFERENCE -> PiiType.LOCATION
                    else -> PiiType.OTHER
                }
                
                flagged.add(
                    FlaggedSegment(
                        id = UUID.randomUUID().toString(),
                        type = piiType,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        originalText = leak.message,
                        confidence = 0.9f
                    )
                )
            }
        }
        
        realName?.clear()
        email?.clear()

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
