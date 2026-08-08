package com.saurabh.artifact.domain.player

import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.*
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.InteractionAction
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.diagnostics.LogKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import com.google.firebase.firestore.FirebaseFirestoreException
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

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
    private val draftRepository: DraftRepository,
    private val diagnosticLogger: DiagnosticLogger
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun execute(
        artifactFlow: Flow<Artifact?>
    ): Flow<PlayerMetadata> {
        return artifactFlow
            .scan(TransitionState()) { state, artifact ->
                val wasJustPublished = (state.artifact != null && artifact != null) &&
                        (state.artifact.id == artifact.id) &&
                        (state.artifact.isDraft && !artifact.isDraft)
                
                if (wasJustPublished) {
                    diagnosticLogger.info(
                        DiagnosticCategory.SYNC,
                        "PLAYER_TRANSITION_PUBLISHED",
                        mapOf(LogKeys.ARTIFACT_ID to artifact.id)
                    )
                }
                
                TransitionState(artifact, wasJustPublished)
            }
            .flatMapLatest { transitionState ->
                val artifact = transitionState.artifact
                if (artifact == null) {
                    flowOf(PlayerMetadata())
                } else {
                    observeMetadata(artifact, transitionState.wasJustPublished)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeMetadata(
        artifact: Artifact,
        wasJustPublished: Boolean
    ): Flow<PlayerMetadata> {
        return if (artifact.isDraft) {
            observeDraftMetadata(artifact)
        } else {
            observePublishedMetadata(artifact, wasJustPublished)
        }
    }

    private fun observeDraftMetadata(artifact: Artifact): Flow<PlayerMetadata> {
        // For drafts, we observe the local draft primarily to handle lifecycle changes
        // (like deletion) while providing empty/default values for all social metadata.
        return draftRepository.observeDraftAsArtifact(artifact.id)
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
        artifact: Artifact,
        wasJustPublished: Boolean
    ): Flow<PlayerMetadata> {
        val userIdFlow = authRepository.currentUser.map { it?.uid }
        
        // Live observation of the artifact itself for real-time counts
        // RECOVERY: Implement bounded retry for transient PERMISSION_DENIED during publishing transition
        val artifactUpdateFlow = artifactRepository.observeArtifact(artifact.id)
            .catch { e ->
                // NARROW FIX: Recognize PERMISSION_DENIED as a transient race condition during publishing
                val isTransientPermissionError = e is FirebaseFirestoreException &&
                        e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                        wasJustPublished
                
                if (isTransientPermissionError) {
                    throw TransientPublishingException()
                } else {
                    throw e
                }
            }
            .map { result ->
                if (result == null && wasJustPublished) {
                    throw TransientPublishingException()
                }
                result
            }
            .retryWhen { cause, attempt ->
                if (cause is TransientPublishingException && attempt < 3) {
                    diagnosticLogger.warn(
                        DiagnosticCategory.FIRESTORE,
                        "ARTIFACT_OBSERVE_RETRYING",
                        mapOf(
                            LogKeys.ARTIFACT_ID to artifact.id,
                            "attempt" to attempt + 1,
                            "delayMs" to 2000
                        )
                    )
                    delay(2.seconds)
                    true
                } else {
                    false
                }
            }
            .catch { e ->
                if (e is TransientPublishingException) {
                    emit(null)
                } else {
                    throw e
                }
            }
            .onStart { emit(artifact) }
            .filterNotNull()

        val resonanceMetadataFlow = userIdFlow.flatMapLatest { currentUserId ->
            reactionRepository.getReactionCounts(artifact.id).map { counts ->
                val isOwner = artifact.userId == currentUserId
                val effectiveCounts = counts ?: ArtifactReactionCounts(
                    artifactId = artifact.id,
                    totalCount = artifact.reactionCount,
                    visibility = artifact.reactionVisibility
                )
                
                val summary = effectiveCounts.getFuzzySummary(isOwner)
                val visibility = effectiveCounts.visibility
                val canShow = visibility != ReactionVisibilityMode.HIDDEN && 
                             (visibility != ReactionVisibilityMode.CREATOR_ONLY || isOwner)

                ResonanceMetadata(
                    count = effectiveCounts.totalCount.toInt(),
                    summary = summary,
                    canShow = canShow
                )
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

        val isResonatingFlow = userIdFlow.flatMapLatest { currentUid ->
            if (currentUid != null && artifact.userId != currentUid) {
                userRepository.observeIsResonating(currentUid, artifact.userId)
            } else {
                flowOf(false)
            }
        }

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
            resonanceMetadataFlow,
            isResonatedFlow,
            resonanceSyncStatusFlow,
            selectedReactionTypeFlow,
            isResonatingFlow,
            followSyncStatusFlow,
            isSavedFlow,
            saveSyncStatusFlow,
            artifactUpdateFlow
        ) { params: Array<Any?> ->
            val res = params[0] as ResonanceMetadata
            PlayerMetadata(
                artifactId = artifact.id,
                resonanceCount = res.count,
                resonanceSummary = res.summary,
                canShowResonators = res.canShow,
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

    private data class ResonanceMetadata(
        val count: Int = 0,
        val summary: String = "",
        val canShow: Boolean = false
    )

    private data class TransitionState(
        val artifact: Artifact? = null,
        val wasJustPublished: Boolean = false
    )

    private class TransientPublishingException : Exception("Transient publishing permission denial")
}

data class PlayerMetadata(
    val artifactId: String = "",
    val resonanceCount: Int = 0,
    val resonanceSummary: String = "",
    val canShowResonators: Boolean = false,
    val isResonated: Boolean = false,
    val resonanceSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val selectedReactionType: ReactionType = ReactionType.I_HEAR_YOU,
    val isResonating: Boolean = false,
    val followSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    val isSaved: Boolean = false,
    val saveSyncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED
)
