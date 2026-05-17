package com.saurabh.artifact.service

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdManager @Inject constructor() {
    private val _isAdEligible = MutableStateFlow(true)

    private var lastAdTime: Long = 0
    private val interactedArtifacts = mutableSetOf<String>()
    private var adsShownInSession = 0

    /**
     * Updates eligibility based on safety level and crisis detection.
     */
    fun updateSafetyContext(safetyResult: SafetyResult) {
        _isAdEligible.value = when {
            safetyResult.isCrisis || safetyResult.level == SafetyLevel.HIGH -> false
            safetyResult.level == SafetyLevel.MEDIUM -> false 
            adsShownInSession >= MAX_ADS_PER_SESSION -> false
            else -> true
        }
    }

    /**
     * Checks if a soft audio ad is eligible to play.
     * MUST be called after content completion, not before playback.
     */
    fun canPlayAudioAd(): Boolean {
        if (!_isAdEligible.value) return false
        
        val now = System.currentTimeMillis()
        val timeSinceLastAd = now - lastAdTime
        
        return timeSinceLastAd >= MIN_AD_INTERVAL_MS && interactedArtifacts.size >= MIN_UNIQUE_INTERACTIONS
    }

    /**
     * Records interaction with a specific artifact to ensure signal integrity.
     */
    fun recordInteraction(artifactId: String) {
        interactedArtifacts.add(artifactId)
    }

    fun recordAdShown() {
        lastAdTime = System.currentTimeMillis()
        interactedArtifacts.clear()
        adsShownInSession++
    }

    companion object {
        private const val MIN_AD_INTERVAL_MS = 300_000 // 5 minutes
        private const val MIN_UNIQUE_INTERACTIONS = 3
        private const val MAX_ADS_PER_SESSION = 5
    }
}
