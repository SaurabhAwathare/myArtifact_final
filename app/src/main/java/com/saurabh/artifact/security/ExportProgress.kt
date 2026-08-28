package com.saurabh.artifact.security

import kotlinx.serialization.Serializable

@Serializable
sealed class ExportProgress {
    @Serializable
    object Starting : ExportProgress()
    
    @Serializable
    object Profile : ExportProgress()
    
    @Serializable
    data class Artifacts(val current: Int, val total: Int) : ExportProgress()
    
    @Serializable
    data class Drafts(val current: Int, val total: Int) : ExportProgress()
    
    @Serializable
    object Participation : ExportProgress()
    
    @Serializable
    object Resonance : ExportProgress()
    
    @Serializable
    object Saved : ExportProgress()
    
    @Serializable
    object Safety : ExportProgress()
    
    @Serializable
    object Finalizing : ExportProgress()
    
    @Serializable
    data class Complete(val hasOmissions: Boolean) : ExportProgress()
    
    @Serializable
    data class Failed(val error: String) : ExportProgress()
}
