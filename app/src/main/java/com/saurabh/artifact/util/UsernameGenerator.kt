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
        "hushed", "gentle", "muted", "still", "low", "serene", "mild", "tranquil",
        "smooth", "breathless", "resting", "sheltered", "hazy", "drifting", "floating",
        "subdued", "placid", "luminous", "celestial", "astral", "solar", "lunar",
        "void", "infinite", "starry", "galactic", "cosmic", "stellar", "nebulous",
        "radiant", "cold", "dark", "deep", "vast", "silent", "remote", "faded",
        "forgotten", "weathered", "old", "worn", "dusty", "lost", "found", "kept",
        "shared", "buried", "sacred", "timeless", "echoing", "ancestral", "stone",
        "golden", "iron", "copper", "glass", "wooden", "clay", "mossy", "wet", "dry",
        "burning", "frozen", "wild", "raw", "pure", "heavy", "light", "clear",
        "spectral", "ghostly", "shadowed", "veiled", "hollow", "transparent", "thin",
        "pale", "dim", "murky", "gloomy", "eerie", "haunting", "wispy", "fluid",
        "transient", "fleeting", "vanishing"
    )

    private val generalNouns = listOf(
        "path", "relic", "shard", "vessel", "trace", "echo", 
        "spire", "prism", "compass", "well", "orb", "lens",
        "breath", "lake", "cloud", "fern", "moss", "whisper", "petal", "rain",
        "mist", "meadow", "valley", "pillow", "feather", "ripple", "stream", "glade",
        "harbor", "shell", "seed", "orbit", "sphere", "void", "horizon", "constellation",
        "flare", "comet", "planet", "star", "nebula", "ray", "beam", "pulse",
        "gravity", "zenith", "nadir", "eclipse", "archive", "fragment", "artifact",
        "scroll", "key", "lock", "mirror", "frame", "clock", "map", "letter",
        "note", "token", "sigil", "ruin", "root", "stone", "spark", "ember",
        "tide", "wave", "peak", "cave", "forest", "branch", "leaf", "thorn",
        "flame", "frost", "ice", "sand", "dust", "soil", "gem", "ore",
        "veil", "shadow", "ghost", "spirit", "mirage", "phantom", "smoke", "aura",
        "halo", "reflection", "glow", "glimmer", "flicker", "shade", "outline", "figure",
        "presence", "form"
    )

    private val emotionalContexts = mapOf(
        "Joy" to (listOf("radiant", "glowing", "vibrant", "rising", "luminous", "pure") to listOf("bloom", "light", "spark", "pulse", "flare", "ray")),
        "Sadness" to (listOf("misty", "fading", "shadowed", "hollow", "pale", "dim") to listOf("rain", "drift", "mist", "tide", "tear", "shade")),
        "Anxiety" to (listOf("steady", "anchored", "deep", "stable", "heavy", "still") to listOf("breath", "center", "root", "stone", "well", "ground")),
        "Peace" to (listOf("serene", "still", "calm", "resting", "mild", "soft") to listOf("lake", "leaf", "feather", "cloud", "meadow", "stream"))
    )

    private val themedLists = mapOf(
        "Calm" to (listOf("quiet", "still", "soft", "serene", "hushed", "gentle", "muted", "low", "patient", "mild", "tranquil", "smooth", "breathless", "resting", "sheltered", "hazy", "drifting", "floating", "subdued", "placid") to listOf("echo", "breath", "lake", "cloud", "fern", "moss", "whisper", "petal", "rain", "mist", "meadow", "valley", "pillow", "feather", "ripple", "stream", "glade", "harbor", "shell", "seed")),
        "Cosmic" to (listOf("luminous", "celestial", "astral", "solar", "lunar", "void", "infinite", "starry", "galactic", "cosmic", "stellar", "nebulous", "radiant", "cold", "dark", "deep", "vast", "silent", "remote") to listOf("prism", "vessel", "path", "orbit", "sphere", "void", "horizon", "constellation", "flare", "comet", "planet", "star", "nebula", "ray", "beam", "pulse", "gravity", "zenith", "nadir", "eclipse")),
        "Memory" to (listOf("faded", "forgotten", "ancient", "weathered", "relic", "old", "worn", "dusty", "silent", "lost", "found", "kept", "shared", "hidden", "buried", "sacred", "timeless", "echoing", "ancestral", "primal") to listOf("archive", "relic", "fragment", "shard", "trace", "artifact", "scroll", "key", "lock", "mirror", "frame", "lens", "clock", "compass", "map", "letter", "note", "token", "sigil", "ruin")),
        "Elemental" to (listOf("stone", "amber", "silver", "golden", "iron", "copper", "glass", "wooden", "clay", "mossy", "wet", "dry", "burning", "frozen", "wild", "raw", "pure", "heavy", "light", "clear") to listOf("root", "stone", "spark", "ember", "tide", "wave", "peak", "cave", "forest", "branch", "leaf", "thorn", "flame", "frost", "ice", "sand", "dust", "soil", "gem", "ore")),
        "Spectral" to (listOf("spectral", "ghostly", "shadowed", "veiled", "hollow", "transparent", "thin", "pale", "dim", "murky", "gloomy", "eerie", "haunting", "wispy", "fluid", "transient", "fleeting", "vanishing", "hidden") to listOf("veil", "shadow", "ghost", "spirit", "mirage", "phantom", "mist", "smoke", "aura", "halo", "reflection", "glow", "glimmer", "spark", "flicker", "shade", "outline", "figure", "presence", "form"))
    )

    /**
     * Generates a single username suggestion based on an optional theme or emotional context.
     * Format: Adjective Noun
     */
    fun generate(theme: String? = null): String {
        val (adjectives, nouns) = when {
            themedLists.containsKey(theme) -> themedLists[theme]!!
            emotionalContexts.containsKey(theme) -> emotionalContexts[theme]!!
            else -> generalAdjectives to generalNouns
        }

        val adj = adjectives.random().replaceFirstChar { it.uppercase() }
        val noun = nouns.random().replaceFirstChar { it.uppercase() }

        return "$adj $noun"
    }

    /**
     * Derives a stable 2-character sigil from an anonymous ID.
     */
    fun deriveSigil(anonymousId: String): String {
        if (anonymousId.isEmpty()) return "A1"
        return anonymousId.takeLast(2).uppercase()
    }

    /**
     * Formats the identity into the public atmospheric string.
     */
    fun formatIdentity(name: String, sigil: String): String {
        return "$name · $sigil"
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
     * Rules: 3-30 characters, Alphanumeric, spaces, and dots allowed for display.
     */
    fun validate(username: String): String? {
        if (username.isEmpty()) return null
        if (username.length < 3) return "Name must be at least 3 characters"
        if (username.length > 30) return "Name must be 30 characters or less"
        
        val regex = Regex("^[a-zA-Z0-9 ·]+$")
        if (!regex.matches(username)) {
            return "Invalid characters in name"
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
