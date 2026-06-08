package com.saurabh.artifact.model

import kotlinx.serialization.Serializable

/**
 * A unified, strongly-typed representation of the publishing lifecycle.
 * This combines database state (ArtifactLifecycle) and network state (SyncStatus)
 * into a single domain model for UI and orchestration.
 */
@Serializable
sealed class PublishState {
    abstract val draftId: String
    abstract val title: String

    @Serializable
    data class Idle(
        override val draftId: String = "",
        override val title: String = ""
    ) : PublishState()

    @Serializable
    data class Preparing(
        override val draftId: String,
        override val title: String,
        val step: PreparationStep = PreparationStep.INITIALIZING
    ) : PublishState()

    @Serializable
    data class Uploading(
        override val draftId: String,
        override val title: String,
        val progress: Float,
        val isWaitingForNetwork: Boolean = false
    ) : PublishState()

    @Serializable
    data class Finalizing(
        override val draftId: String,
        override val title: String
    ) : PublishState()

    @Serializable
    data class Published(
        override val draftId: String,
        override val title: String,
        val artifactId: String
    ) : PublishState()

    @Serializable
    data class Error(
        override val draftId: String,
        override val title: String,
        val message: String
    ) : PublishState()

    enum class PreparationStep {
        INITIALIZING
    }
}
