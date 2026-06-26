package com.saurabh.artifact.domain.review

import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.model.EngagementStatus
import com.saurabh.artifact.repository.CommentUnlockRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Use case to determine the current engagement state of an artifact.
 * Centralizes the logic of combining authoritative server state (Firestore)
 * with optimistic pending state (local queue).
 */
class GetEngagementStateUseCase @Inject constructor(
    private val commentUnlockRepository: CommentUnlockRepository,
    private val pendingInteractionDao: PendingInteractionDao,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository
) {
    /**
     * Executes the observation of engagement state for a specific artifact.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun execute(artifactId: String): Flow<EngagementStatus> {
        val isServerUnlockedFlow = commentUnlockRepository.isUnlocked(artifactId)
        
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

        return combine(isServerUnlockedFlow, isPendingSyncFlow) { isUnlocked, isPending ->
            when {
                isUnlocked -> EngagementStatus.UNLOCKED
                isPending -> EngagementStatus.PENDING_VALIDATION
                else -> EngagementStatus.LOCKED
            }
        }.distinctUntilChanged()
    }
}
