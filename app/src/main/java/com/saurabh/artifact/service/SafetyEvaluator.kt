package com.saurabh.artifact.service

import android.util.Log
import com.saurabh.artifact.model.EmotionalTone
import com.saurabh.artifact.model.PromptCategory
import com.saurabh.artifact.model.ReflectionPrompt
import javax.inject.Inject
import javax.inject.Singleton

enum class SafetyLevel {
    LOW, MEDIUM, HIGH
}

data class SafetyResult(
    val level: SafetyLevel,
    val matchedPattern: String? = null,
    val suggestedPrompt: ReflectionPrompt? = null,
    val isCrisis: Boolean = false,
    val confidence: Float = 1.0f
)

@Singleton
class SafetyEvaluator @Inject constructor() {

    private val tag = "SafetyEvaluator"
    private val negationPrefixes = listOf("don't", "not", "never", "won't", "stopped", "isn't", "aren't", "wasn't")
    private val exclusionContexts = listOf("movie", "song", "lyrics", "book", "character", "quote", "story", "video", "film")

    // Expanded Regex patterns for flexible matching (handles word boundaries and punctuation)
    private val highRiskPatterns = listOf(
        Regex("\\b(want to disappear|i wish i wasn't here|can't take this anymore)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(hurt myself|end it all|suicide|kill myself|giving up on life)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(better off dead|no point (in living|going on))\\b", RegexOption.IGNORE_CASE)
    )

    private val mediumRiskPatterns = listOf(
        Regex("\\b(hopeless|alone|isolated|empty|trapped|stuck)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(nothing matters|no one cares|hurting inside)\\b", RegexOption.IGNORE_CASE)
    )

    private val lowDistressKeywords = listOf("sad", "overwhelmed", "tired", "stressed", "worried", "anxious")

    /**
     * Evaluates text for emotional risk. 
     * Uses Regex for high/medium and keywords for low to ensure deterministic performance.
     */
    fun evaluate(text: String?): SafetyResult {
        if (text.isNullOrBlank()) return SafetyResult(SafetyLevel.LOW)
        
        // Normalization: clean punctuation and extra spaces for more reliable matching
        val normalized = text.lowercase().replace(Regex("[^a-z\\s']"), " ").replace(Regex("\\s+"), " ").trim()

        // 1. Check High Risk (Crisis)
        highRiskPatterns.forEach { pattern ->
            if (pattern.containsMatchIn(normalized)) {
                val confidence = evaluateConfidence(normalized, pattern.pattern)
                val finalLevel = if (confidence < 0.6f) SafetyLevel.MEDIUM else SafetyLevel.HIGH
                
                logWarning("HIGH RISK PATTERN MATCHED: ${pattern.pattern} (Confidence: $confidence -> Level: $finalLevel)")
                
                return SafetyResult(
                    level = finalLevel,
                    matchedPattern = pattern.pattern,
                    isCrisis = true,
                    confidence = confidence,
                    suggestedPrompt = ReflectionPrompt(
                        id = "safety_high",
                        question = if (finalLevel == SafetyLevel.HIGH) 
                            "You don’t have to go through this moment alone. What feels most important right now?" 
                        else "What might bring a small sense of ease in this moment?",
                        category = PromptCategory.GENERAL,
                        tone = if (finalLevel == SafetyLevel.HIGH) EmotionalTone.HEAVY else EmotionalTone.GENTLE
                    )
                )
            }
        }

        // 2. Check Medium Risk
        mediumRiskPatterns.forEach { pattern ->
            if (pattern.containsMatchIn(normalized)) {
                val confidence = evaluateConfidence(normalized, pattern.pattern)
                logDebug("MEDIUM RISK PATTERN MATCHED: ${pattern.pattern} (Confidence: $confidence)")

                return SafetyResult(
                    level = SafetyLevel.MEDIUM,
                    matchedPattern = pattern.pattern,
                    confidence = confidence,
                    suggestedPrompt = ReflectionPrompt(
                        id = "safety_medium",
                        question = "What might bring a small sense of ease in this moment?",
                        category = PromptCategory.GENERAL,
                        tone = EmotionalTone.GENTLE
                    )
                )
            }
        }

        // 3. Check Low Distress
        val hasLowDistress = lowDistressKeywords.any { normalized.contains(it) }
        if (hasLowDistress) {
            return SafetyResult(SafetyLevel.LOW)
        }

        return SafetyResult(SafetyLevel.LOW)
    }

    private fun logWarning(message: String) {
        try {
            Log.w(tag, message)
        } catch (_: Exception) {
            println("[$tag] WARNING: $message")
        }
    }

    private fun logDebug(message: String) {
        try {
            Log.d(tag, message)
        } catch (_: Exception) {
            println("[$tag] DEBUG: $message")
        }
    }

    private fun evaluateConfidence(text: String, matchedPattern: String): Float {
        var confidence = 1.0f
        
        // 1. Negation Check (High Impact)
        if (hasNegationNearby(text, matchedPattern)) {
            confidence -= 0.5f
        }
        
        // 2. Context Check (Moderate Impact)
        if (exclusionContexts.any { text.contains(it) }) {
            confidence -= 0.3f
        }
        
        return confidence.coerceAtLeast(0.1f)
    }

    private fun hasNegationNearby(text: String, matchedPattern: String): Boolean {
        // Find the index of the match in the text
        val regex = Regex(matchedPattern, RegexOption.IGNORE_CASE)
        val matchResult = regex.find(text) ?: return false
        val matchStartIndex = matchResult.range.first
        
        // Get all text before the match
        val textBefore = text.substring(0, matchStartIndex).trim()
        val wordsBefore = textBefore.split(Regex("\\s+")).filter { it.isNotEmpty() }
        
        if (wordsBefore.isNotEmpty()) {
            // Check ANY word before the match for a negation (simple heuristic for "not going to...")
            // Limit to last 5 words to keep it "nearby"
            val nearbyWords = wordsBefore.takeLast(5).map { it.lowercase() }
            
            if (nearbyWords.any { negationPrefixes.contains(it) }) {
                return true
            }
        }

        return false
    }

    /**
     * Filters/Normalizes AI output to meet strict tone and structure guardrails.
     */
    fun filterAIOutput(prompt: String): String {
        var filtered = prompt.trim()

        // 1. Output Normalization: Force single sentence
        val sentences = filtered.split(Regex("(?<=[.!?])\\s*"))
        if (sentences.isNotEmpty()) {
            filtered = sentences[0]
        }

        // 2. Formatting: Ensure it's a question
        if (!filtered.endsWith("?")) {
            filtered = filtered.replace(Regex("[.!?]$"), "") + "?"
        }

        // 3. Tone Guardrails: Remove accusatory or directive language
        val lower = filtered.lowercase()
        
        // Handle "Why"
        if (lower.startsWith("why ")) {
            filtered = "What makes " + filtered.substring(4)
        }

        // Handle Directives & Minimizing Language
        val replacements = mapOf(
            Regex("\\byou (should|must|need to|have to)\\b", RegexOption.IGNORE_CASE) to "how might you",
            Regex("\\bdo (this|that)\\b", RegexOption.IGNORE_CASE) to "what if you",
            Regex("\\btry to\\b", RegexOption.IGNORE_CASE) to "consider if",
            Regex("\\bjust\\b", RegexOption.IGNORE_CASE) to "" // Remove minimizing "just"
        )
        
        replacements.forEach { (pattern, replacement) ->
            filtered = pattern.replace(filtered, replacement)
        }

        // Final cleanup of double spaces possibly introduced by "just" removal
        return filtered.replace(Regex("\\s+"), " ").trim()
    }
}
