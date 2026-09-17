package com.saurabh.artifact.util

import com.saurabh.artifact.model.Emotion

/**
 * Maps canonical emotional states for UI filtering and queries.
 * Each canonical emotion is its own filter.
 */
object EmotionCategoryMapper {

    /**
     * Returns a list of matching emotions for a given UI filter chip.
     * Maps canonical emotion directly to itself.
     */
    fun getRelatedEmotions(uiCategory: String): List<String> {
        val matchedEmotion = Emotion.entries.find { 
            it.label.equals(uiCategory, ignoreCase = true) || it.name.equals(uiCategory, ignoreCase = true) 
        }
        return if (matchedEmotion != null) {
            listOf(matchedEmotion.label)
        } else {
            listOf(uiCategory)
        }
    }

    /**
     * Determines which UI category a specific internal emotion belongs to.
     */
    fun getCategoryForEmotion(internalEmotion: String): String {
        val matchedEmotion = Emotion.entries.find { 
            it.label.equals(internalEmotion, ignoreCase = true) || it.name.equals(internalEmotion, ignoreCase = true) 
        }
        return matchedEmotion?.label ?: internalEmotion
    }
}
