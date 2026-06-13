package com.saurabh.artifact.model

import androidx.appsearch.annotation.Document

/**
 * AppSearch document representing user preferences and interaction history.
 * Converted to Kotlin to ensure reliable KSP processing for AppSearch.
 */
@Document
data class UserPreferenceDocument(
    @Document.Namespace
    val namespace: String = "",

    @Document.Id
    val id: String = "",

    @Document.StringProperty
    val primaryGoal: String? = null,

    @Document.StringProperty
    val goals: List<String> = emptyList(),

    @Document.StringProperty
    val dominantEmotion: String? = null,

    @Document.LongProperty
    val lastInteractionTimestamp: Long = System.currentTimeMillis(),
)
