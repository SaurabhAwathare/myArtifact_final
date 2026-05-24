package com.saurabh.artifact.domain

import com.saurabh.artifact.model.ModerationWarning
import com.saurabh.artifact.model.ValidationReason
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A privacy-preserving engine that detects potential identity leaks.
 * Analyzes usernames, transcripts, and content against real-world identity markers.
 */
@Singleton
class IdentityScout @Inject constructor() {

    private val commonEmailDomains = setOf("gmail", "yahoo", "outlook", "hotmail", "icloud", "protonmail")

    /**
     * Scans a target string (username or content) for leaks of the user's real identity.
     */
    fun detectLeaks(
        target: String,
        realName: String?,
        email: String?
    ): List<ModerationWarning> {
        val warnings = mutableListOf<ModerationWarning>()
        if (target.isBlank()) return warnings

        val normalizedTarget = normalize(target)
        val nameTokens = tokenizeName(realName)
        val emailPrefix = extractEmailPrefix(email)

        // 1. Check for Real Name Tokens & Phonetic Motifs
        for (token in nameTokens) {
            if (token.length >= 3 && normalizedTarget.contains(token)) {
                warnings.add(
                    ModerationWarning(
                        ValidationReason.REAL_NAME,
                        "This looks a bit like your real name. For your safety, consider a more anonymous choice."
                    )
                )
                break 
            }
            
            // Check for Motif Reuse (Phonetic similarity)
            if (token.length >= 4 && isPhoneticallySimilar(normalizedTarget, token)) {
                warnings.add(
                    ModerationWarning(
                        ValidationReason.MOTIF_REUSE,
                        "This presence feels familiar to your real identity. Try something more distinct to stay safe."
                    )
                )
            }
        }

        // 2. Check for Email Prefix
        if (emailPrefix != null && emailPrefix.length >= 3 && normalizedTarget.contains(emailPrefix)) {
            warnings.add(
                ModerationWarning(
                    ValidationReason.EMAIL_ADDRESS,
                    "This name is very similar to your email. Try something more unique to stay anonymous."
                )
            )
        }

        // 2.5 General Email Pattern Check
        if (target.contains("@") || target.contains(".com") || target.contains(".net")) {
            warnings.add(
                ModerationWarning(
                    ValidationReason.EMAIL_ADDRESS,
                    "This name looks like an email address. For your privacy, avoid using email-like names."
                )
            )
        }

        // 3. Simple Phone Number Detection (7+ digits)
        val digits = target.filter { it.isDigit() }
        if (digits.length >= 7) {
            warnings.add(
                ModerationWarning(
                    ValidationReason.PHONE_NUMBER,
                    "Using phone numbers as names can make you easy to find. Stay safe and avoid using them."
                )
            )
        }

        // 4. Behavioral Patterns (Introductions & Pivots)
        detectBehavioralLeaks(target, warnings)

        return warnings.distinctBy { it.reason }
    }

    private fun detectBehavioralLeaks(target: String, warnings: MutableList<ModerationWarning>) {
        val introductionPatterns = listOf("i'm", "i am", "name is", "call me")
        val contactPatterns = listOf("follow me", "dm me", "contact me", "reach out", "handle is")
        
        val lowercaseTarget = target.lowercase(Locale.ROOT)

        if (introductionPatterns.any { lowercaseTarget.contains(it) }) {
            warnings.add(
                ModerationWarning(
                    ValidationReason.INTRODUCTION_PATTERN,
                    "Self-introductions can lead to accidental identity leaks. Consider a more reflective approach."
                )
            )
        }

        if (contactPatterns.any { lowercaseTarget.contains(it) }) {
            warnings.add(
                ModerationWarning(
                    ValidationReason.CONTACT_PIVOT,
                    "Directing others to external platforms can break the sanctuary of your anonymity."
                )
            )
        }
    }

    /**
     * Calculates a weighted risk score (0.0 to 1.0) based on detected warnings.
     */
    fun calculateRiskScore(warnings: List<ModerationWarning>): Float {
        if (warnings.isEmpty()) return 0f
        
        var score = 0f
        for (warning in warnings) {
            score += when (warning.reason) {
                ValidationReason.EMAIL_ADDRESS, 
                ValidationReason.PHONE_NUMBER -> 1.0f
                ValidationReason.REAL_NAME -> 0.8f
                ValidationReason.MOTIF_REUSE -> 0.6f
                ValidationReason.CONTACT_PIVOT -> 0.7f
                ValidationReason.INTRODUCTION_PATTERN -> 0.5f
                ValidationReason.TRIANGULATION_RISK -> 0.5f
                else -> 0.1f
            }
        }
        
        return score.coerceIn(0f, 1f)
    }

    /**
     * Normalizes a string by lowercasing and removing common delimiters.
     */
    fun normalize(input: String): String {
        return input.lowercase(Locale.ROOT)
            .replace("_", "")
            .replace(".", "")
            .replace("-", "")
            .trim()
    }

    /**
     * Splits a real name into searchable tokens, filtering out common titles or very short parts.
     */
    private fun tokenizeName(name: String?): List<String> {
        if (name.isNullOrBlank()) return emptyList()
        return name.lowercase(Locale.ROOT)
            .split(Regex("[\\s,.-]+"))
            .filter { it.length >= 2 }
    }

    /**
     * Extracts the prefix from an email address (e.g., 'saurabh' from 'saurabh@gmail.com').
     */
    private fun extractEmailPrefix(email: String?): String? {
        if (email.isNullOrBlank()) return null
        val prefix = email.substringBefore("@").lowercase(Locale.ROOT)
        return normalize(prefix)
    }

    /**
     * Detects phonetic similarity using a simplified Levenshtein-style approach 
     * or partial matches. (Placeholder for more advanced phonetic algorithms).
     */
    fun isPhoneticallySimilar(a: String, b: String): Boolean {
        // For production, we'd use Metaphone or Double Metaphone.
        // For this implementation, we'll use a distance threshold.
        val normA = normalize(a)
        val normB = normalize(b)
        
        if (normA.contains(normB) || normB.contains(normA)) return true
        
        // Simple Levenshtein check for small distances
        return levenshteinDistance(normA, normB) <= 2
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
