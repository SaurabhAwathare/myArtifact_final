package com.saurabh.artifact.repository

import android.util.Base64
import android.util.Log
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequest
import androidx.work.ExistingWorkPolicy
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.saurabh.artifact.data.local.*
import com.saurabh.artifact.domain.review.EngagementEvidence
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.util.BitSet
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EngagementRepositoryQueueTest {
    private val engagementDao = mockk<EngagementDao>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val pendingInteractionDao = mockk<PendingInteractionDao>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val gson = Gson()

    private lateinit var repository: EngagementRepository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } returns "mocked_base64"

        every { authRepository.currentUserId } returns "user123"

        repository = EngagementRepository(
            engagementDao = engagementDao,
            authRepository = authRepository,
            pendingInteractionDao = pendingInteractionDao,
            workManager = workManager,
            firestore = firestore,
            gson = gson
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `saveEngagement should insert interaction when unified queue is enabled`() = runTest(timeout = 10.seconds) {
        val evidence = EngagementEvidence(
            artifactId = "art123",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet().apply { set(0, 100) },
            lastPositionMs = 5000L,
            furthestPositionMs = 5000L,
            hasReachedEnd = false
        )

        val result = repository.saveEngagement(evidence)
        
        if (result.isFailure) {
            println("Test failed with: ${result.exceptionOrNull()}")
        }
        assert(result.isSuccess)

        coVerify(exactly = 1) { 
            pendingInteractionDao.insert(any()) 
        }
        
        verify { workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `saveEngagement should collapse existing engagement interaction for same artifact`() = runTest {
        val evidence = EngagementEvidence(
            artifactId = "art123",
            versionTag = "v1",
            durationMs = 10000L,
            coverage = BitSet().apply { set(0, 100) },
            lastPositionMs = 5000L,
            furthestPositionMs = 5000L,
            hasReachedEnd = false
        )

        repository.saveEngagement(evidence)
        
        coVerify { 
            pendingInteractionDao.deleteByType("art123", "user123", InteractionType.ENGAGEMENT)
        }
        coVerify {
            pendingInteractionDao.insert(match { it.interactionType == InteractionType.ENGAGEMENT })
        }
    }
}
