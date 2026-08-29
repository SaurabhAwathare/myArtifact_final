package com.saurabh.artifact.ui.util

import com.saurabh.artifact.model.CommunityAtmosphere

/**
 * Responsible for converting a CommunityAtmosphere snapshot into a qualitative, 
 * poetic statement for the community.
 */
object AtmosphereMapper {

    /**
     * The diversity threshold: an emotion category must represent at least 15% 
     * of the recent artifacts to be considered part of the "atmosphere".
     */
    private const val DIVERSITY_THRESHOLD = 0.15f

    fun mapToStatement(atmosphere: CommunityAtmosphere): String {
        if (atmosphere.status == "INSUFFICIENT_DATA" || atmosphere.totalArtifacts < 10) {
            return "The Human Library is resonating quietly today."
        }

        val total = atmosphere.totalArtifacts.toFloat()
        
        // Filter categories that meet the diversity threshold
        val prominentCategories = atmosphere.categoryCounts
            .filter { (_, count) -> (count.toFloat() / total) >= DIVERSITY_THRESHOLD }
            .keys
            .toList()

        if (prominentCategories.isEmpty()) {
            return "The Human Library is carrying many different feelings today."
        }

        // Deterministic but "fair" selection based on the generation timestamp
        // This ensures the message doesn't change on every feed refresh within the 4h window,
        // but rotates which prominent emotion is shown if multiple exist.
        val index = (atmosphere.generatedAt.seconds % prominentCategories.size).toInt()
        val category = prominentCategories[index]

        return when (category) {
            "Happy" -> "Today, many people are reflecting on Hope."
            "Sad" -> "The community is holding space for grief and reflection today."
            "Anxious" -> "There is a sense of collective uncertainty in the air today."
            "Neutral" -> "The Human Library is finding a moment of calm today."
            "Motivated" -> "Many are sharing moments of energy and purpose today."
            "Lonely" -> "The community is resonating with shared solitude today."
            "Mixed" -> "The Human Library is echoing with many complex stories today."
            "Grateful" -> "Today, many are pausing for Gratitude."
            else -> "The Human Library is resonating with $category today."
        }
    }
}
