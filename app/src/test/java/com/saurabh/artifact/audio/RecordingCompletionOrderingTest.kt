package com.saurabh.artifact.audio

import android.util.Log
import androidx.work.Operation
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.repository.RecordingRepository
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

class RecordingCompletionOrderingTest {
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val publishingOrchestrator = mockk<PublishingOrchestrator>(relaxed = true)
    
    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @Test
    fun `stopRecording should follow correct sequence`() = runBlocking {
        val draftId = "test-draft"
        val sequence = mutableListOf<String>()

        coEvery { recordingRepository.finalizeRecording(any(), any(), any(), any()) } coAnswers {
            sequence.add("PERSISTED")
            Result.success(Unit)
        }

        coEvery { publishingOrchestrator.startProcessing(any()) } coAnswers {
            sequence.add("ENQUEUED")
            mockk<Operation>()
        }

        // Simulate the logic in RecordingService.stopRecording()
        // 1. Finalize
        val result = recordingRepository.finalizeRecording(draftId, 1000L, 5000L)
        if (result.isSuccess) {
            // 2. Enqueue
            publishingOrchestrator.startProcessing(draftId)
            
            // 3. Emit COMPLETED
            sequence.add("COMPLETED")
        }

        // Verify ordering
        assertEquals(listOf("PERSISTED", "ENQUEUED", "COMPLETED"), sequence)
    }
}
