package com.saurabh.artifact.domain.player

import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.InteractionAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Use case to aggregate all user-artifact relationship metadata for the player.
 * Consolidates observation of reactions, resonance, saved status, and unlock state.
 */
class GetPlayerContextUseCase @Inject constructor(
    private val artifactRepository: ArtifactRepository,
    private val reactionRepository: ReactionRepository,
    private val userRepository: UserRepository,
    private val savedArtifactManager: SavedArtifactManager,
    private val authRepository: AuthRepository,
    private val pendingInteractionDao: com.saurabh.artifact.data.local.PendingInteractionDao,
    private val draftRepository: DraftRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun execute(
        artifactFlow: Flow<Artifact?>
    ): Flow<PlayerMetadata> {
        return artifactFlow.flatMapLatest { artifact ->
            if (artifact == null) {
                flowOf(PlayerMetadata())
            } else {
                observeMetadata(artifact)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeMetadata(
        artifact: Artifact
    ): Flow<PlayerMetadata> {
        return if (artifact.isDraft) {
            observeDraftMetadata(artifact)
        } else {
            observePublishedMetadata(artifact)
        }
    }

    private fun observeDraftMetadata(artifact: Artifact): Flow<PlayerMetadata> {
        // For drafts, we observe the local draft for title/waveform updates
        // but provide empty/default values for all social metadata.
        return draftRepository.observeDraftAsArtifact(artifact.id)
            .onStart { emit(artifact) }
            .map { updatedArtifact ->
                if (updatedArtifact == null) {
                    PlayerMetadata()
                } else {
                    PlayerMetadata(artifactId = updatedArtifact.id)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePublishedMetadata(
        artifact: Artifact
    ): Flow<PlayerMetadata> {
        val userIdFlow = authRepository.currentUser.map { it?.uid }
        
        // Live observation of the artifact itself for real-time counts
        val artifactUpdateFlow = artifactRepository.observeArtifact(artifact.id)
            .onStart { emit(artifact) }
            .filterNotNull()

        val resonanceSummaryFlow = userIdFlow.flatMapLatest { currentUserId ->
            reactionRepository.getReactionCounts(artifact.id).map { counts ->
                val isOwner = artifact.userId == currentUserId
                counts?.getFuzzySummary(isOwner) 
                    ?: ArtifactReactionCounts(
                        artifactId = artifact.id,
                        totalCount = artifact.reactionCount,
                        visibility = artifact.reactionVisibility
                    ).getFuzzySummary(isOwner)
            }
        }

        val reactionsFlow = userIdFlow.flatMapLatest { uid ->
            if (uid != null) {
                reactionRepository.getArtifactReactions(artifact.id, uid)
            } else {
                flowOf(emptyList())
            }
        }

        val pendingInteractionsFlow = userIdFlow.flatMapLatest { uid ->
            if (uid != null) {
                pendingInteractionDao.observePendingForArtifact(artifact.id, uid)
            } else {
                flowOf(emptyList())
            }
        }

        val isResonatedFlow = combine(reactionsFlow, pendingInteractionsFlow) { reactions, pending ->
            val pendingAdd = pending.any { 
                it.interactionType == InteractionType.REACTION && 
                it.action == InteractionAction.ADD 
            }
            val pendingRemove = pending.any { 
                it.interactionType == InteractionType.REACTION && 
                it.action == InteractionAction.REMOVE 
            }
            
            when {
                pendingAdd -> true
                pendingRemove -> false
                else -> reactions.isNotEmpty()
            }
        }

        val resonanceSyncStatusFlow = pendingInteractionsFlow.map { pending ->
            if (pending.any { it.interactionType == InteractionType.REACTION }) InteractionSyncStatus.PENDING else InteractionSyncStatus.SYNCED
        }

        val selectedReactionTypeFlow = combine(reactionsFlow, pendingInteractionsFlow) { reactions, pending ->
            val pendingAdd = pending.find { 
                it.interactionType == InteractionType.REACTION && 
                it.action == InteractionAction.ADD 
            }
            pendingAdd?.metadata?.let { ReactionType.fromId(it) } 
                ?: reactions.firstOrNull()?.let { ReactionType.fromId(it.typeId) } 
                ?: ReactionType.I_HEAR_YOU
        }

        val isResonatingFlow = combine(userIdFlow) { uid ->
            val currentUid = uid.firstOrNull() 
            if (currentUid != null && artifact.userId != currentUid) {
                userRepository.observeIsResonating(currentUid, artifact.userId)
            } else {
                flowOf(false)
            }
        }.flatMapLatest { it }

        val followSyncStatusFlow = pendingInteractionsFlow.map { pending ->
            if (pending.any { it.interactionType == InteractionType.FOLLOW }) InteractionSyncStatus.PENDING else InteractionSyncStatus.SYNCED
        }

        val isSavedFlow = combine(savedArtifactManager.savedIds, pendingInteractionsFlow) { savedIds, pending ->
            val pendingAdd = pending.any { 
                it.interactionType == InteractionType.SAVE && 
                it.action == InteractionAction.ADD 
            }
            val pendingRemove = pending.any { 
                it.interactionType == InteractionType.SAVE && 
                it.action == InteractionAction.REMOVE 
            }
            
            when {
                pendingAdd -> true
                pendingRemove -> false
                else -> savedIds.contains(artifact.id)
            }
        }

        val saveSyncStatusFlow = pendingInteractionsFlow.map { pending ->
            if (pending.any { it.interactionType == InteractionType.SAVE }) InteractionSyncStatus.PENDING else InteractionSyncStatus.SYNCED
        }

        return combine(
            resonanceSummaryFlow,
            isResonatedFlow,
            resonanceSyncStatusFlow,
            selectedReactionTypeFlow,
            isResonatingFlow,
            followSyncStatusFlow,
            isSavedFlow,
            saveSyncStatusFlow,
            artifactUpdateFlow
        ) { params: Array<Any?> ->
            val updatedArtifact = params[8] as Artifact
            PlayerMetadata(
                artifactId = artifact.id,
                resonanceSummary = params[0] as String,
                isResonated = params[1] as Boolean,
                resonanceSyncStatus = params[2] as InteractionSyncStatus,
                selectedReactionType = params[3] as ReactionType,
                isResonating = params[4] as Boolean,
                followSyncStatus = params[5] as InteractionSyncStatus,
                isSaved = params[6] as Boolean,
                saveSyncStatus = params[7] as InteractionSyncStatus
            )
        }
    }
}

data class PlayerMetadata(
    val artifactId: String = "",
    val resonanceSummary: String = "",
    val isResonated: Boolean = false,
    val resonanceSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val selectedReactionType: ReactionType = ReactionType.I_HEAR_YOU,
    val isResonating: Boolean = false,
    val followSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val isSaved: Boolean = false,
    val saveSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED
)
