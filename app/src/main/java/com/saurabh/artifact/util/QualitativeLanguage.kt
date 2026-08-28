package com.saurabh.artifact.util

/**
 * Utility for converting numerical counts into qualitative human-centric language.
 * Designed to reduce status competition and focus on meaningful resonance.
 */
object QualitativeLanguage {

    /**
     * Maps a relationship count (followers/following) to a qualitative description.
     */
    fun getResonanceLabel(count: Long): String {
        return when {
            count <= 0 -> "no resonance yet"
            count == 1L -> "a single soul"
            count in 2..5 -> "a few souls"
            count in 6..20 -> "many souls"
            count in 21..100 -> "a vast circle"
            else -> "a boundless echo"
        }
    }
}
