package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
sealed class ProcessingStatus {
    @Serializable
    object Idle : ProcessingStatus()
    
    @Serializable
    data class Active(val stage: ProcessingStage) : ProcessingStatus()
    
    @Serializable
    object Completed : ProcessingStatus()
    
    @Serializable
    object Failed : ProcessingStatus()
}

@Serializable
enum class ProcessingStage {
    SAVING,
    TRANSCODING,
    NORMALIZING,
    WAVEFORM_GENERATION,
    TRANSCRIBING,
    PRIVACY_SCANNING,
    SAFETY_CHECK,
    ENCRYPTING_BACKUP
}
