package com.saurabh.artifact.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class OnboardingStep {
    WELCOME,
    PHILOSOPHY,
    PRIVACY,
    USERNAME,
    AVATAR,
    REFLECTION,
    NOTIFICATIONS,
    COMPLETE
}

@Immutable
data class OnboardingState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val username: String = "",
    val avatarSeed: String = "",
    val notificationsEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)
