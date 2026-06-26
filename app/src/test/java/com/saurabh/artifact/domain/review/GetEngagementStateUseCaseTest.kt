package com.saurabh.artifact.domain.review

import com.google.firebase.auth.FirebaseUser
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.model.EngagementStatus
import com.saurabh.artifact.repository.CommentUnlockRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GetEngagementStateUseCaseTest {

    private val commentUnlockRepository = mockk<CommentUnlockRepository>()
    private val pendingInteractionDao = mockk<PendingInteractionDao>()
    private val authRepository = mockk<com.saurabh.artifact.repository.AuthRepository>()
    private val useCase = GetEngagementStateUseCase(commentUnlockRepository, pendingInteractionDao, authRepository)

    @Test
    fun `when server says unlocked, status is UNLOCKED`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns userId }
        every { commentUnlockRepository.isUnlocked(artifactId) } returns flowOf(true)
        every { authRepository.currentUser } returns MutableStateFlow(firebaseUser)
        every { pendingInteractionDao.observePendingForArtifact(artifactId, userId) } returns flowOf(emptyList())

        useCase.execute(artifactId).take(1).collect { status ->
            assertEquals(EngagementStatus.UNLOCKED, status)
        }
    }

    @Test
    fun `when server says locked but queue has engagement, status is PENDING_VALIDATION`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns userId }
        every { commentUnlockRepository.isUnlocked(artifactId) } returns flowOf(false)
        every { authRepository.currentUser } returns MutableStateFlow(firebaseUser)
        every { pendingInteractionDao.observePendingForArtifact(artifactId, userId) } returns flowOf(
            listOf(
                PendingInteractionEntity(
                    userId = userId,
                    artifactId = artifactId,
                    interactionType = InteractionType.ENGAGEMENT,
                    action = "ADD"
                )
            )
        )

        useCase.execute(artifactId).take(1).collect { status ->
            assertEquals(EngagementStatus.PENDING_VALIDATION, status)
        }
    }

    @Test
    fun `when server says locked and queue is empty, status is LOCKED`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns userId }
        every { commentUnlockRepository.isUnlocked(artifactId) } returns flowOf(false)
        every { authRepository.currentUser } returns MutableStateFlow(firebaseUser)
        every { pendingInteractionDao.observePendingForArtifact(artifactId, userId) } returns flowOf(emptyList())

        useCase.execute(artifactId).take(1).collect { status ->
            assertEquals(EngagementStatus.LOCKED, status)
        }
    }
}
