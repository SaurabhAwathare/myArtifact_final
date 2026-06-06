package com.saurabh.artifact.domain.player

import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.ArtifactReactionCounts
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.repository.*
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
    private val commentUnlockRepository: CommentUnlockRepository,
    private val authRepository: AuthRepository,
    private val pendingInteractionDao: com.saurabh.artifact.data.local.PendingInteractionDao
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
        val userIdFlow = authRepository.currentUser.map { it?.uid }
        
        val isUnlockedFlow = commentUnlockRepository.isUnlocked(artifact.id)
        
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
                        totalCount = artifact.reactionCount.toInt(),
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

        val pendingInteractionsFlow = pendingInteractionDao.observePendingForArtifact(artifact.id)

        val isResonatedFlow = combine(reactionsFlow, pendingInteractionsFlow) { reactions, pending ->
            val pendingAdd = pending.any { 
                it.interactionType == com.saurabh.artifact.data.local.InteractionType.REACTION && 
                it.action == com.saurabh.artifact.data.local.InteractionAction.ADD 
            }
            val pendingRemove = pending.any { 
                it.interactionType == com.saurabh.artifact.data.local.InteractionType.REACTION && 
                it.action == com.saurabh.artifact.data.local.InteractionAction.REMOVE 
            }
            
            when {
                pendingAdd -> true
                pendingRemove -> false
                else -> reactions.isNotEmpty()
            }
        }

        val selectedReactionTypeFlow = combine(reactionsFlow, pendingInteractionsFlow) { reactions, pending ->
            val pendingAdd = pending.find { 
                it.interactionType == com.saurabh.artifact.data.local.InteractionType.REACTION && 
                it.action == com.saurabh.artifact.data.local.InteractionAction.ADD 
            }
            pendingAdd?.metadata?.let { ReactionType.fromId(it) } 
                ?: reactions.firstOrNull()?.let { ReactionType.fromId(it.typeId) } 
                ?: ReactionType.I_HEAR_YOU
        }

        val isResonatingFlow = combine(userIdFlow) { uid ->
            val currentUid = uid.firstOrNull() // userIdFlow is a Flow<String?>
            if (currentUid != null && artifact.userId != currentUid) {
                userRepository.observeIsResonating(currentUid, artifact.userId)
            } else {
                flowOf(false)
            }
        }.flatMapLatest { it }

        val isSavedFlow = combine(savedArtifactManager.savedIds, pendingInteractionsFlow) { savedIds, pending ->
            val pendingAdd = pending.any { 
                it.interactionType == com.saurabh.artifact.data.local.InteractionType.SAVE && 
                it.action == com.saurabh.artifact.data.local.InteractionAction.ADD 
            }
            val pendingRemove = pending.any { 
                it.interactionType == com.saurabh.artifact.data.local.InteractionType.SAVE && 
                it.action == com.saurabh.artifact.data.local.InteractionAction.REMOVE 
            }
            
            when {
                pendingAdd -> true
                pendingRemove -> false
                else -> savedIds.contains(artifact.id)
            }
        }

        return combine(
            isUnlockedFlow,
            resonanceSummaryFlow,
            isResonatedFlow,
            selectedReactionTypeFlow,
            isResonatingFlow,
            isSavedFlow,
            artifactUpdateFlow
        ) { params: Array<Any?> ->
            val updatedArtifact = params[6] as Artifact
            PlayerMetadata(
                artifactId = artifact.id,
                isCommentUnlocked = params[0] as Boolean,
                resonanceSummary = params[1] as String,
                isResonated = params[2] as Boolean,
                selectedReactionType = params[3] as ReactionType,
                isResonating = params[4] as Boolean,
                isSaved = params[5] as Boolean,
                commentCount = updatedArtifact.commentCount
            )
        }
    }
}

data class PlayerMetadata(
    val artifactId: String = "",
    val isCommentUnlocked: Boolean = false,
    val resonanceSummary: String = "",
    val isResonated: Boolean = false,
    val selectedReactionType: ReactionType = ReactionType.I_HEAR_YOU,
    val isResonating: Boolean = false,
    val isSaved: Boolean = false,
    val commentCount: Long = 0
)
