package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProcessingStage {
    AUDIO_NORMALIZATION,
    WAVEFORM_EXTRACTION,
    SAFETY_ANALYSIS,
    FAILED
}
