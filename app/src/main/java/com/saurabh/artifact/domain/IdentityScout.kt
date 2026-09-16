package com.saurabh.artifact.domain

import com.saurabh.artifact.model.ModerationWarning
import com.saurabh.artifact.model.ValidationReason
import com.saurabh.artifact.repository.NameCorpusRepository
import com.saurabh.artifact.util.SecureString
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A privacy-preserving engine that detects potential identity leaks.
 * Analyzes usernames, transcripts, and content against real-world identity markers
 * and an offline name corpus.
 */
@Singleton
class IdentityScout @Inject constructor(
    private val nameCorpusRepository: NameCorpusRepository
) {
    /**
     * No-arg constructor for manual or test instantiation without explicit DI.
     */
    constructor() : this(NameCorpusRepository())

    companion object {
        // Known default and application-role tokens to exclude from personal real-name leak detection
        private val IGNORED_ROLE_TOKENS = setOf(
            "artifact",
            "creator",
            "user",
            "admin",
            "anonymous",
            "guest",
            "default",
            "test",
            "persona",
            "system"
        )

        // Common dual-meaning words that double as names but should not trigger blocking
        // real-name leak warnings on their own.
        private val DUAL_MEANING_WORDS = setOf(
            "amber", "rose", "dawn", "king", "may", "grace", "hope",
            "faith", "summer", "autumn", "violet", "sky", "river",
            "stone", "star", "joy", "reed", "cliff", "glen", "dale",
            "ray", "miles", "quiet", "art", "cat"
        )
    }

    /**
     * Scans a target string (username or content) for leaks of the user's real identity
     * or real-world names from the name corpus.
     */
    fun detectLeaks(
        target: String,
        realName: SecureString?,
        email: SecureString?
    ): List<ModerationWarning> {
        val warnings = mutableListOf<ModerationWarning>()
        if (target.isBlank()) return warnings

        val normalizedTarget = normalize(target)
        val plainRealName = realName?.toUnsecureString()
        val plainEmail = email?.toUnsecureString()

        val nameTokens = tokenizeName(plainRealName)
        val emailPrefix = extractEmailPrefix(plainEmail)
        val targetWords = tokenizeTarget(target)

        // 1. Check for Real Name Tokens & Phonetic Motifs (User Provided Real Name)
        for (token in nameTokens) {
            val hasExactWordMatch = targetWords.contains(token) || 
                Regex("\\b${Regex.escape(token)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(target)

            if (token.length >= 3 && hasExactWordMatch) {
                warnings.add(
                    ModerationWarning(
                        reason = ValidationReason.REAL_NAME,
                        message = "This looks a bit like your real name. For your safety, consider a more anonymous choice.",
                        isBlocking = true
                    )
                )
                break 
            }
            
            // Check for Motif Reuse (Phonetic similarity on target words)
            if (token.length >= 4) {
                val hasPhoneticMatch = targetWords.any { word ->
                    word.length >= 3 && isWordPhoneticallySimilar(word, token)
                }
                if (hasPhoneticMatch) {
                    warnings.add(
                        ModerationWarning(
                            reason = ValidationReason.MOTIF_REUSE,
                            message = "This presence feels familiar to your real identity. Try something more distinct to stay safe.",
                            isBlocking = false
                        )
                    )
                }
            }
        }

        // 1.5 Check for Corpus Real Names (Offline Dictionary Lookup)
        val corpusMatch = targetWords.firstOrNull { token ->
            token.length >= 3 &&
                !IGNORED_ROLE_TOKENS.contains(token) &&
                !DUAL_MEANING_WORDS.contains(token) &&
                nameCorpusRepository.contains(token)
        }

        if (corpusMatch != null) {
            warnings.add(
                ModerationWarning(
                    reason = ValidationReason.REAL_NAME,
                    message = "This looks like a real name. For your privacy and safety, consider a pseudonymous choice.",
                    isBlocking = true
                )
            )
        }

        // 2. Check for Email Prefix
        if (emailPrefix != null && emailPrefix.length >= 3 && normalizedTarget.contains(emailPrefix)) {
            warnings.add(
                ModerationWarning(
                    reason = ValidationReason.EMAIL_ADDRESS,
                    message = "This name is very similar to your email. Try something more unique to stay anonymous.",
                    isBlocking = true
                )
            )
        }

        // 2.5 General Email Pattern Check
        if (target.contains("@") || target.contains(".com") || target.contains(".net")) {
            warnings.add(
                ModerationWarning(
                    reason = ValidationReason.EMAIL_ADDRESS,
                    message = "This name looks like an email address. For your privacy, avoid using email-like names.",
                    isBlocking = true
                )
            )
        }

        // 3. Simple Phone Number Detection (7+ digits)
        val digits = target.filter { it.isDigit() }
        if (digits.length >= 7) {
            warnings.add(
                ModerationWarning(
                    reason = ValidationReason.PHONE_NUMBER,
                    message = "Using phone numbers as names can make you easy to find. Stay safe and avoid using them.",
                    isBlocking = true
                )
            )
        }

        // 4. Behavioral Patterns (Introductions & Pivots)
        detectBehavioralLeaks(target, warnings)

        // 5. Potentially Identifying Handle Patterns
        detectPotentiallyIdentifyingHandles(target, warnings)

        return warnings.distinctBy { it.reason }
    }

    private fun detectBehavioralLeaks(target: String, warnings: MutableList<ModerationWarning>) {
        val introductionPatterns = listOf("i'm", "i am", "name is", "call me")
        val contactPatterns = listOf("follow me", "dm me", "contact me", "reach out", "handle is")
        
        val lowercaseTarget = target.lowercase(Locale.ROOT)

        if (introductionPatterns.any { lowercaseTarget.contains(it) }) {
            warnings.add(
                ModerationWarning(
                    reason = ValidationReason.INTRODUCTION_PATTERN,
                    message = "Self-introductions can lead to accidental identity leaks. Consider a more reflective approach.",
                    isBlocking = false
                )
            )
        }

        if (contactPatterns.any { lowercaseTarget.contains(it) }) {
            warnings.add(
                ModerationWarning(
                    reason = ValidationReason.CONTACT_PIVOT,
                    message = "Directing others to external platforms can break the sanctuary of your anonymity.",
                    isBlocking = false
                )
            )
        }
    }

    private fun detectPotentiallyIdentifyingHandles(target: String, warnings: MutableList<ModerationWarning>) {
        val lower = target.lowercase(Locale.ROOT)
        val identityPrefixes = listOf("real_", "official_", "iam_")
        if (identityPrefixes.any { lower.startsWith(it) && lower.length > it.length + 2 }) {
            warnings.add(
                ModerationWarning(
                    reason = ValidationReason.POTENTIALLY_IDENTIFYING,
                    message = "This name appears to assert a personal or official identity. Consider a more anonymous choice.",
                    isBlocking = false
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
                ValidationReason.POTENTIALLY_IDENTIFYING -> 0.4f
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
     * Splits a target string into word tokens for word-boundary matching.
     * Supports camelCase/PascalCase boundaries, spaces, underscores, hyphens, dots,
     * middle dots, digits, and strips diacritics.
     */
    fun tokenizeTarget(target: String): List<String> {
        if (target.isBlank()) return emptyList()

        val rawTokens = target.split(Regex("[\\s,._\\-\\d·]+"))
            .map { stripDiacritics(it).trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<String>()
        for (raw in rawTokens) {
            val normalized = raw.lowercase(Locale.ROOT)
            result.add(normalized)

            val camelCaseSplit = raw
                .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
                .replace(Regex("(?<=[A-Za-z])(?=[0-9])"), " ")
                .replace(Regex("(?<=[0-9])(?=[A-Za-z])"), " ")
                .split(" ")
                .map { it.lowercase(Locale.ROOT).trim() }
                .filter { it.isNotBlank() }

            for (sub in camelCaseSplit) {
                if (sub != normalized) {
                    result.add(sub)
                }
            }
        }

        return result.distinct()
    }

    private fun stripDiacritics(input: String): String {
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    /**
     * Splits a real name into searchable tokens, filtering out common titles,
     * application role tokens, and short parts to prevent false positives.
     */
    private fun tokenizeName(name: String?): List<String> {
        if (name.isNullOrBlank()) return emptyList()
        return name.lowercase(Locale.ROOT)
            .split(Regex("[\\s,.-]+"))
            .filter { token ->
                token.length >= 3 && !IGNORED_ROLE_TOKENS.contains(token)
            }
    }

    /**
     * Extracts the prefix from an email address (e.g., 'user' from 'user@example.com').
     */
    private fun extractEmailPrefix(email: String?): String? {
        if (email.isNullOrBlank()) return null
        val prefix = email.substringBefore("@").lowercase(Locale.ROOT)
        val normalized = normalize(prefix)
        return if (IGNORED_ROLE_TOKENS.contains(normalized)) null else normalized
    }

    /**
     * Checks if two individual words are phonetically similar or close in Levenshtein distance.
     */
    fun isWordPhoneticallySimilar(word: String, token: String): Boolean {
        val normA = normalize(word)
        val normB = normalize(token)
        if (normA.isEmpty() || normB.isEmpty()) return false
        
        if (normA == normB) return true
        
        val codeA = metaphone(normA)
        val codeB = metaphone(normB)
        if (codeA.isNotEmpty() && codeB.isNotEmpty() && codeA == codeB) return true
        
        return levenshteinDistance(normA, normB) <= 2 && minOf(normA.length, normB.length) >= 4
    }

    /**
     * Detects phonetic similarity using the Metaphone algorithm.
     * This catches names that sound similar even if spelled differently.
     */
    fun isPhoneticallySimilar(a: String, b: String): Boolean {
        return isWordPhoneticallySimilar(a, b)
    }

    /**
     * Simplified Metaphone algorithm for phonetic name matching.
     */
    fun metaphone(input: String): String {
        var txt = input.uppercase(Locale.ROOT).filter { it.isLetter() }
        if (txt.isEmpty()) return ""

        val sb = StringBuilder()
        
        // 1. Drop duplicate adjacent letters (except C)
        val cleanTxt = StringBuilder()
        for (i in txt.indices) {
            if (i == 0 || txt[i] != txt[i-1] || txt[i] == 'C') {
                cleanTxt.append(txt[i])
            }
        }
        txt = cleanTxt.toString()

        var i = 0
        while (i < txt.length) {
            // Basic Metaphone rules
            when (val c = txt[i]) {
                'A', 'E', 'I', 'O', 'U' -> if (i == 0) sb.append(c) // Only keep vowels at the start
                'B' -> sb.append('B')
                'C' -> {
                    // C -> X (SH) if followed by IA or H, else S if followed by I, E, Y, else K
                    if (i + 1 < txt.length) {
                        when (txt[i + 1]) {
                            'H' -> {
                                sb.append('X')
                                i++
                            }
                            'I', 'E', 'Y' -> sb.append('S')
                            else -> sb.append('K')
                        }
                    } else {
                        sb.append('K')
                    }
                }
                'D' -> {
                    if (i + 1 < txt.length && txt[i + 1] == 'G' && (i + 2 < txt.length && (txt[i + 2] == 'E' || txt[i + 2] == 'I' || txt[i + 2] == 'Y'))) {
                        sb.append('J')
                        i++
                    } else {
                        sb.append('T')
                    }
                }
                'F', 'J', 'L', 'M', 'N', 'R' -> sb.append(c)
                'G' -> {
                    if (i + 1 < txt.length) {
                        when (txt[i + 1]) {
                            'I', 'E', 'Y' -> sb.append('J')
                            else -> sb.append('K')
                        }
                    } else {
                        sb.append('K')
                    }
                }
                'H' -> {
                    // Skip 'H' unless it's at the start or follows a vowel (very basic heuristic)
                    if (i == 0 || "AEIOU".contains(txt[i-1])) {
                        // Do nothing (H is silent in many phonetic cases unless it modifies previous letter)
                        // But for simplicity in this name-leak detector, we treat it as potentially significant if at start
                        if (i == 0) sb.append('H')
                    }
                }
                'K' -> if (i > 0 && txt[i - 1] == 'C') { /* Skip */ } else sb.append('K')
                'P' -> if (i + 1 < txt.length && txt[i + 1] == 'H') {
                    sb.append('F')
                    i++
                } else sb.append('P')
                'Q' -> sb.append('K')
                'S' -> {
                    if (i + 1 < txt.length && txt[i + 1] == 'H') {
                        sb.append('X')
                        i++
                    } else {
                        sb.append('S')
                    }
                }
                'T' -> {
                    if (i + 1 < txt.length) {
                        when (txt[i + 1]) {
                            'H' -> {
                                sb.append('0') // Theta
                                i++
                            }
                            'I' -> {
                                if (i + 2 < txt.length && (txt[i + 2] == 'A' || txt[i + 2] == 'O')) {
                                    sb.append('X')
                                } else {
                                    sb.append('T')
                                }
                            }
                            else -> sb.append('T')
                        }
                    } else {
                        sb.append('T')
                    }
                }
                'V' -> sb.append('F')
                'W', 'Y' -> if (i + 1 < txt.length && "AEIOU".contains(txt[i+1])) sb.append(c)
                'X' -> { sb.append('K'); sb.append('S') }
                'Z' -> sb.append('S')
            }
            i++
        }
        
        return sb.toString()
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
