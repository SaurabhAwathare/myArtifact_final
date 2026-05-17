package com.saurabh.artifact.util

import java.util.Locale
import kotlin.random.Random

/**
 * A thoughtful username generation utility that produces emotionally neutral, 
 * non-toxic, and reflective identity markers.
 */
object UsernameGenerator {

    private val generalAdjectives = listOf(
        "quiet", "ancient", "ethereal", "nomadic", "silver", "hidden", 
        "primal", "amber", "distant", "weathered", "soft", "patient",
    )

    private val generalNouns = listOf(
        "path", "relic", "shard", "vessel", "trace", "echo", 
        "spire", "prism", "compass", "well", "orb", "lens"
    )

    private val emotionalContexts = mapOf(
        "Joy" to (listOf("radiant", "glowing", "vibrant", "rising") to listOf("bloom", "light", "spark", "pulse")),
        "Sadness" to (listOf("misty", "fading", "shadowed", "hollow") to listOf("rain", "drift", "mist", "tide")),
        "Anxiety" to (listOf("steady", "anchored", "deep", "stable") to listOf("breath", "center", "root", "stone")),
        "Peace" to (listOf("serene", "still", "calm", "resting") to listOf("lake", "leaf", "feather", "cloud"))
    )

    private val themedLists = mapOf(
        "Calm" to (listOf("quiet", "still", "soft", "serene") to listOf("river", "lake", "leaf", "snow")),
        "Poetic" to (listOf("ethereal", "amber", "silver", "faded") to listOf("echo", "trace", "letters", "prism")),
        "Cozy" to (listOf("warm", "golden", "resting", "mild") to listOf("hearth", "window", "blanket", "tea")),
        "Reflective" to (listOf("distant", "patient", "deep", "ancient") to listOf("well", "mirror", "orbit", "shard"))
    )

    /**
     * Generates a single username suggestion based on an optional theme or emotional context.
     * Format: adjective_noun_number
     */
    fun generate(theme: String? = null): String {
        val (adjectives, nouns) = when {
            themedLists.containsKey(theme) -> themedLists[theme]!!
            emotionalContexts.containsKey(theme) -> emotionalContexts[theme]!!
            else -> generalAdjectives to generalNouns
        }

        val adj = adjectives.random()
        val noun = nouns.random()
        val suffix = Random.nextInt(10, 100)

        return "${adj}_${noun}_$suffix".lowercase(Locale.ROOT)
    }

    /**
     * Generates a unique list of username suggestions.
     */
    fun generateSuggestions(count: Int, theme: String? = null): List<String> {
        val suggestions = mutableSetOf<String>()
        var attempts = 0
        val maxAttempts = count * 5

        while (suggestions.size < count && attempts < maxAttempts) {
            suggestions.add(generate(theme))
            attempts++
        }

        return suggestions.toList()
    }

    /**
     * Validates a username against system constraints.
     * Rules: 3-20 characters, lowercase alphanumeric and underscores only.
     */
    fun validate(username: String): String? {
        if (username.isEmpty()) return null
        if (username.length < 3) return "Name must be at least 3 characters"
        if (username.length > 20) return "Name must be 20 characters or less"
        
        val regex = Regex("^[a-z0-9_]+$")
        if (!regex.matches(username)) {
            return "Only lowercase letters, numbers, and underscores allowed"
        }
        
        return null
    }

    /**
     * Generates a unique list of username suggestions based on a base name.
     */
    fun generateSuggestionsForBase(base: String, count: Int = 3): List<String> {
        if (base.length < 3) return emptyList()
        
        val cleanedBase = base.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '_' }
        if (cleanedBase.isEmpty()) return emptyList()

        val suffixes = listOf("_", "01", "dev", "user", "x", "pro", "io")
        val suggestions = mutableSetOf<String>()
        
        // Strategy 1: Base + Random Numbers
        while (suggestions.size < count / 2 + 1) {
            val num = Random.nextInt(10, 999)
            suggestions.add("${cleanedBase}$num".take(20))
        }

        // Strategy 2: Base + Suffix + Random Numbers
        while (suggestions.size < count) {
            val suffix = suffixes.random()
            val num = Random.nextInt(1, 99)
            suggestions.add("${cleanedBase}_${suffix}${num}".take(20))
        }

        return suggestions.toList().filter { it != base }
    }

    fun isValid(username: String): Boolean = validate(username) == null
}
