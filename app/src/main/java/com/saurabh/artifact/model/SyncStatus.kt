package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
sealed class SyncStatus {
    @Serializable
    object LocalOnly : SyncStatus()
    
    @Serializable
    object Queued : SyncStatus()

    @Serializable
    object WaitingForNetwork : SyncStatus()
    
    @Serializable
    object Uploading : SyncStatus()
    
    @Serializable
    object Synced : SyncStatus()

    @Serializable
    object Finalizing : SyncStatus()

    @Serializable
    object Recovering : SyncStatus()
    
    @Serializable
    data class Failed(val error: String) : SyncStatus()
}
