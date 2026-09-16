package com.saurabh.artifact.util

import java.util.Locale

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
        
        val regex = Regex("^[a-zA-Z0-9_@. ·]+$")
        if (!regex.matches(username)) {
            return "Invalid characters in name"
        }
        
        return null
    }

    private val reservedSystemNames = setOf(
        "admin", "administrator", "system", "artifact", "root", "support", "mod", "moderator", "official"
    )

    /**
     * Generates a unique list of username suggestions based on a base name.
     * Produces pseudonymous atmospheric personas rather than echoing user input.
     */
    fun generateSuggestionsForBase(base: String, count: Int = 3): List<String> {
        if (count <= 0) return emptyList()

        val rawTrimmed = base.trim()
        val cleanedInput = rawTrimmed.lowercase(Locale.ROOT)
        val filterTarget = cleanedInput.filter { it.isLetterOrDigit() }

        val suggestions = LinkedHashSet<String>()
        val themes = themedLists.keys.toList()
        var attempts = 0
        val maxAttempts = count * 20

        while (suggestions.size < count && attempts < maxAttempts) {
            val theme = if (attempts < themes.size) themes[attempts] else themes.random()
            val candidate = generate(theme)

            val candidateLower = candidate.lowercase(Locale.ROOT)
            val candidateNormalized = candidateLower.filter { it.isLetterOrDigit() }

            val isValidFormat = validate(candidate) == null
            val isReserved = reservedSystemNames.contains(candidateLower)

            // Avoid echoing base string if base length >= 3
            val containsRawBase = filterTarget.length >= 3 && 
                (candidateLower.contains(filterTarget) || candidateNormalized.contains(filterTarget))
            val matchesBase = cleanedInput.isNotEmpty() && candidateLower == cleanedInput

            if (isValidFormat && !isReserved && !containsRawBase && !matchesBase) {
                suggestions.add(candidate)
            }
            attempts++
        }

        // Fallback using general generator if themes didn't yield enough unique suggestions
        while (suggestions.size < count && attempts < maxAttempts * 2) {
            val candidate = generate()
            if (validate(candidate) == null) {
                suggestions.add(candidate)
            }
            attempts++
        }

        return suggestions.toList()
    }

    fun isValid(username: String): Boolean = validate(username) == null
}
