package com.saurabh.artifact.domain.review

import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
import com.saurabh.artifact.domain.review.comments.CommentUnlockValidator
import com.saurabh.artifact.domain.review.comments.LocalEligibility
import com.saurabh.artifact.model.EngagementStatus
import com.saurabh.artifact.repository.CommentUnlockRepository
import com.saurabh.artifact.repository.EngagementRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Use case to determine the current engagement state of an artifact.
 * Centralizes the logic of combining authoritative server state (Firestore),
 * persistent local evidence (Room), and optimistic pending state (local queue).
 */
class GetEngagementStateUseCase @Inject constructor(
    private val commentUnlockRepository: CommentUnlockRepository,
    private val engagementRepository: EngagementRepository,
    private val pendingInteractionDao: PendingInteractionDao,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val commentUnlockValidator: CommentUnlockValidator,
    private val commentUnlockPolicy: CommentUnlockPolicy
) {
    /**
     * Executes the observation of engagement state for a specific artifact.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun execute(artifactId: String): Flow<EngagementStatus> {
        val isServerUnlockedFlow = commentUnlockRepository.isUnlocked(artifactId)
        
        val localEvidenceFlow = engagementRepository.observeEngagementEvidence(artifactId)

        val isPendingSyncFlow = authRepository.currentUser.flatMapLatest { user ->
            val userId = user?.uid
            if (userId != null) {
                pendingInteractionDao.observePendingForArtifact(artifactId, userId)
                    .map { pending ->
                        pending.any { it.interactionType == InteractionType.ENGAGEMENT }
                    }
            } else {
                kotlinx.coroutines.flow.flowOf(false)
            }
        }

        return combine(
            isServerUnlockedFlow,
            localEvidenceFlow,
            isPendingSyncFlow
        ) { isServerUnlocked, localEvidence, isPendingSync ->
            val eligibility = commentUnlockValidator.getEligibility(
                localEvidence,
                commentUnlockPolicy,
                isServerUnlocked
            )

            val status = resolveStatus(eligibility, isPendingSync)
            com.saurabh.artifact.util.ArtifactLogger.d("EngagementState", "Resolved status for $artifactId: $status (server=$isServerUnlocked, pending=$isPendingSync)")
            status
        }.distinctUntilChanged()
    }

    private fun resolveStatus(
        eligibility: LocalEligibility,
        isPendingSync: Boolean
    ): EngagementStatus {
        return when {
            eligibility == LocalEligibility.ELIGIBLE_SERVER_CONFIRMED -> EngagementStatus.UNLOCKED
            eligibility == LocalEligibility.ELIGIBLE_LOCAL -> EngagementStatus.VERIFYING
            isPendingSync -> EngagementStatus.VERIFYING
            else -> EngagementStatus.LOCKED
        }
    }
}
