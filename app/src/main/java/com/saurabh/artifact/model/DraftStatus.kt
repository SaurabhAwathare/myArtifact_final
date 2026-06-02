package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

@Serializable
data class DraftStatus(
    val lifecycle: ArtifactLifecycle = ArtifactLifecycle.RECORDING,
    val processing: ProcessingStatus = ProcessingStatus.Idle,
    val publication: SyncStatus = SyncStatus.LocalOnly,
    val backup: SyncStatus = SyncStatus.LocalOnly
)
