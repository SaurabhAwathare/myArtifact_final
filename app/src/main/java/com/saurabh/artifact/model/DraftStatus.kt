package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
data class DraftStatus(
    val lifecycle: ArtifactLifecycle = ArtifactLifecycle.RECORDING,
    val processing: ProcessingStatus = ProcessingStatus.Idle,
    val sync: SyncStatus = SyncStatus.LocalOnly
)
