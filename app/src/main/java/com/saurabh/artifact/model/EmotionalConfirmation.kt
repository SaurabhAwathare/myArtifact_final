package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
data class EmotionalConfirmation(
    val draftId: String,
    val isComfortable: Boolean,
    val wantsToReviewAgain: Boolean,
    val savePrivately: Boolean,
    val isUnsure: Boolean,
    val confidenceScore: Float, // 0.0 to 1.0
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class EmotionalRiskAssessment(
    val riskScore: Float, // 0.0 to 1.0
    val detectedEmotions: List<Emotion>,
    val requiresCooldown: Boolean,
    val guidanceMessage: String? = null,
    val isHighIntensity: Boolean = riskScore > 0.7f
)

@Serializable
data class ReflectionSafetyFlags(
    val piiDetected: Boolean,
    val emotionalOverwhelm: Boolean,
    val privacyExposureRisk: Boolean,
    val readinessChecked: Boolean
)
