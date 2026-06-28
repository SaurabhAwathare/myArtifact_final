package com.saurabh.artifact.domain.review

import com.google.firebase.auth.FirebaseUser
import com.saurabh.artifact.data.local.InteractionType
import com.saurabh.artifact.data.local.PendingInteractionDao
import com.saurabh.artifact.data.local.PendingInteractionEntity
import com.saurabh.artifact.domain.review.comments.CommentUnlockPolicy
import com.saurabh.artifact.domain.review.comments.CommentUnlockValidator
import com.saurabh.artifact.model.EngagementStatus
import com.saurabh.artifact.repository.CommentUnlockRepository
import com.saurabh.artifact.repository.EngagementRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.BitSet

@OptIn(ExperimentalCoroutinesApi::class)
class GetEngagementStateUseCaseTest {

    private val commentUnlockRepository = mockk<CommentUnlockRepository>()
    private val engagementRepository = mockk<EngagementRepository>()
    private val pendingInteractionDao = mockk<PendingInteractionDao>()
    private val authRepository = mockk<com.saurabh.artifact.repository.AuthRepository>()
    private val commentUnlockValidator = CommentUnlockValidator()
    private val commentUnlockPolicy = CommentUnlockPolicy()
    
    private val useCase = GetEngagementStateUseCase(
        commentUnlockRepository,
        engagementRepository,
        pendingInteractionDao,
        authRepository,
        commentUnlockValidator,
        commentUnlockPolicy
    )

    @Test
    fun `when server says unlocked, status is UNLOCKED`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns userId }
        every { commentUnlockRepository.isUnlocked(artifactId) } returns flowOf(true)
        every { engagementRepository.observeEngagementEvidence(artifactId) } returns flowOf(null)
        every { authRepository.currentUser } returns MutableStateFlow(firebaseUser)
        every { pendingInteractionDao.observePendingForArtifact(artifactId, userId) } returns flowOf(emptyList())

        useCase.execute(artifactId).take(1).collect { status ->
            assertEquals(EngagementStatus.UNLOCKED, status)
        }
    }

    @Test
    fun `when server says locked but local evidence qualifies, status is VERIFYING`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns userId }
        
        // Full coverage evidence
        val coverage = BitSet()
        coverage.set(0, 100)
        val evidence = EngagementEvidence(
            artifactId = artifactId,
            versionTag = "v1",
            durationMs = 10000,
            coverage = coverage,
            hasReachedEnd = true
        )

        every { commentUnlockRepository.isUnlocked(artifactId) } returns flowOf(false)
        every { engagementRepository.observeEngagementEvidence(artifactId) } returns flowOf(evidence)
        every { authRepository.currentUser } returns MutableStateFlow(firebaseUser)
        every { pendingInteractionDao.observePendingForArtifact(artifactId, userId) } returns flowOf(emptyList())

        useCase.execute(artifactId).take(1).collect { status ->
            assertEquals(EngagementStatus.VERIFYING, status)
        }
    }

    @Test
    fun `when server says locked but queue has engagement, status is VERIFYING`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns userId }
        every { commentUnlockRepository.isUnlocked(artifactId) } returns flowOf(false)
        every { engagementRepository.observeEngagementEvidence(artifactId) } returns flowOf(null)
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
            assertEquals(EngagementStatus.VERIFYING, status)
        }
    }

    @Test
    fun `when server says locked and no evidence or queue, status is LOCKED`() = runTest {
        val artifactId = "art123"
        val userId = "user123"
        val firebaseUser = mockk<FirebaseUser> { every { uid } returns userId }
        every { commentUnlockRepository.isUnlocked(artifactId) } returns flowOf(false)
        every { engagementRepository.observeEngagementEvidence(artifactId) } returns flowOf(null)
        every { authRepository.currentUser } returns MutableStateFlow(firebaseUser)
        every { pendingInteractionDao.observePendingForArtifact(artifactId, userId) } returns flowOf(emptyList())

        useCase.execute(artifactId).take(1).collect { status ->
            assertEquals(EngagementStatus.LOCKED, status)
        }
    }
}
