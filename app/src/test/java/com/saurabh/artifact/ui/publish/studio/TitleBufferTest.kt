package com.saurabh.artifact.ui.publish.studio

import android.util.Log
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.ReviewState
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.domain.IdentityScout
import com.saurabh.artifact.domain.PublishArtifactUseCase
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.DraftStatus
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.RecordingRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TitleBufferTest {
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val playbackCoordinator = mockk<PlaybackCoordinator>(relaxed = true)
    private val publishArtifactUseCase = mockk<PublishArtifactUseCase>(relaxed = true)
    private val identityScout = mockk<IdentityScout>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        
        val draftId = "test-draft"
        val draftFlow = MutableStateFlow<ArtifactDraftEntity?>(
            ArtifactDraftEntity(
                id = draftId,
                localAudioPath = "/path/audio.wav",
                lifecycle = ArtifactLifecycle.REVIEW_REQUIRED,
                title = "Initial Title",
                status = DraftStatus()
            )
        )
        
        every { recordingRepository.observeDraft(draftId) } returns draftFlow
        every { playbackCoordinator.reviewProgress } returns MutableStateFlow(ReviewState())
        every { playbackCoordinator.isPlaying } returns MutableStateFlow(false)
        every { playbackCoordinator.playbackSpeed } returns MutableStateFlow(1.0f)
        every { playbackCoordinator.playbackCompletedEvent } returns MutableSharedFlow<String>()
        every { playbackCoordinator.duration } returns flowOf(0.milliseconds)
    }

    @Test
    fun `updateTitle should update local buffer immediately and Room after debounce`() = runTest {
        val viewModel = PublishingStudioViewModel(
            recordingRepository,
            playbackCoordinator,
            publishArtifactUseCase,
            identityScout,
            authRepository
        )

        val draftId = "test-draft"
        viewModel.loadDraft(draftId)
        
        // Wait for sessionState to load the draft
        viewModel.sessionState.first { it.draftId == draftId }
        
        // Initial state
        assertEquals("Initial Title", viewModel.sessionState.value.title)

        // Update title
        viewModel.updateTitle("New Title")

        // Verify: UI state shows new title IMMEDIATELY (from buffer)
        assertEquals("New Title", viewModel.sessionState.value.title)

        // Verify: Room update NOT called yet (before debounce)
        coVerify(exactly = 0) { recordingRepository.updateDraftMetadata(draftId, "New Title", any()) }

        // Wait for debounce (500ms)
        advanceTimeBy(600)
        runCurrent() // Trigger the debounced job

        // Verify: Room update CALLED
        coVerify(exactly = 1) { recordingRepository.updateDraftMetadata(draftId, "New Title", any()) }
    }
}
