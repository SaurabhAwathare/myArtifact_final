package com.saurabh.artifact.ui.drafts.list

import com.saurabh.artifact.audio.ArtifactCleanupManager
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.domain.PublishingOrchestrator
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.DraftWithUpload
import com.saurabh.artifact.repository.RecordingRepository
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.model.DraftStatus
import com.saurabh.artifact.model.ProcessingStatus
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftListViewModelTest {
    private val recordingRepository = mockk<RecordingRepository>(relaxed = true)
    private val draftRepository = mockk<DraftRepository>(relaxed = true)
    private val publishingOrchestrator = mockk<PublishingOrchestrator>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val cleanupManager = mockk<ArtifactCleanupManager>(relaxed = true)
    private val audioPlayer = mockk<PlaybackCoordinator>(relaxed = true)

    private lateinit var viewModel: DraftListViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { audioPlayer.currentArtifact } returns MutableStateFlow(null)
        every { audioPlayer.isPlaying } returns MutableStateFlow(false)
        every { audioPlayer.isBuffering } returns MutableStateFlow(false)
        
        viewModel = DraftListViewModel(
            recordingRepository,
            draftRepository,
            publishingOrchestrator,
            authRepository,
            cleanupManager,
            audioPlayer
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `retryProcessing should call publishingOrchestrator startProcessing`() = runTest {
        val draftId = "draft-123"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
        }
        val draftWithUpload = DraftWithUpload(draft, null)

        viewModel.retryProcessing(draftWithUpload)

        coVerify { publishingOrchestrator.startProcessing(draftId) }
    }

    @Test
    fun `onDraftClicked should retry if processing failed`() = runTest {
        val draftId = "draft-failed"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
            every { status } returns DraftStatus(processing = ProcessingStatus.Failed)
        }
        val draftWithUpload = DraftWithUpload(draft, null)

        viewModel.onDraftClicked(draftWithUpload)

        coVerify { publishingOrchestrator.startProcessing(draftId) }
    }

    @Test
    fun `playDraftAudio when not currently playing should start draft preview`() = runTest {
        val draftId = "draft-preview-456"
        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
        }

        viewModel.playDraftAudio(draft)

        verify { audioPlayer.playDraftPreview(draftId) }
        verify(exactly = 0) { audioPlayer.togglePlayPause() }
    }

    @Test
    fun `playDraftAudio when already playing should toggle play pause`() = runTest {
        val draftId = "draft-preview-playing"
        val mockArtifact = mockk<com.saurabh.artifact.model.Artifact>(relaxed = true) {
            every { id } returns draftId
        }
        every { audioPlayer.currentArtifact } returns MutableStateFlow(mockArtifact)

        val playingVm = DraftListViewModel(
            recordingRepository,
            draftRepository,
            publishingOrchestrator,
            authRepository,
            cleanupManager,
            audioPlayer
        )

        val draft = mockk<ArtifactDraftEntity>(relaxed = true) {
            every { id } returns draftId
        }

        playingVm.playDraftAudio(draft)

        verify { audioPlayer.togglePlayPause() }
    }

    @Test
    fun `playbackProgress should be 0f initially when nothing is playing`() = runTest {
        val currentArtifactFlow = MutableStateFlow<com.saurabh.artifact.model.Artifact?>(null)
        val reviewProgressFlow = MutableStateFlow(com.saurabh.artifact.audio.ReviewState())
        every { audioPlayer.currentArtifact } returns currentArtifactFlow
        every { audioPlayer.reviewProgress } returns reviewProgressFlow

        val vm = DraftListViewModel(
            recordingRepository,
            draftRepository,
            publishingOrchestrator,
            authRepository,
            cleanupManager,
            audioPlayer
        )

        org.junit.Assert.assertEquals(0f, vm.playbackProgress.value, 0.001f)
    }

    @Test
    fun `playbackProgress should match reviewProgress when currentArtifact matches reviewProgress artifactId`() = runTest {
        val draftId = "draft-playing-1"
        val mockArtifact = mockk<com.saurabh.artifact.model.Artifact>(relaxed = true) {
            every { id } returns draftId
        }
        val currentArtifactFlow = MutableStateFlow<com.saurabh.artifact.model.Artifact?>(mockArtifact)
        val reviewProgressFlow = MutableStateFlow(
            com.saurabh.artifact.audio.ReviewState(
                artifactId = draftId,
                durationMs = 10000L,
                furthestPositionMs = 4500L
            )
        )
        every { audioPlayer.currentArtifact } returns currentArtifactFlow
        every { audioPlayer.reviewProgress } returns reviewProgressFlow

        val vm = DraftListViewModel(
            recordingRepository,
            draftRepository,
            publishingOrchestrator,
            authRepository,
            cleanupManager,
            audioPlayer
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.playbackProgress.collect()
        }

        org.junit.Assert.assertEquals(0.45f, vm.playbackProgress.value, 0.001f)
    }

    @Test
    fun `playbackProgress should be 0f when reviewProgress artifactId does not match currentArtifact id`() = runTest {
        val draftId = "draft-active"
        val mockArtifact = mockk<com.saurabh.artifact.model.Artifact>(relaxed = true) {
            every { id } returns draftId
        }
        val currentArtifactFlow = MutableStateFlow<com.saurabh.artifact.model.Artifact?>(mockArtifact)
        val reviewProgressFlow = MutableStateFlow(
            com.saurabh.artifact.audio.ReviewState(
                artifactId = "different-draft-id",
                durationMs = 10000L,
                furthestPositionMs = 7000L
            )
        )
        every { audioPlayer.currentArtifact } returns currentArtifactFlow
        every { audioPlayer.reviewProgress } returns reviewProgressFlow

        val vm = DraftListViewModel(
            recordingRepository,
            draftRepository,
            publishingOrchestrator,
            authRepository,
            cleanupManager,
            audioPlayer
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.playbackProgress.collect()
        }

        org.junit.Assert.assertEquals(0f, vm.playbackProgress.value, 0.001f)
    }

    @Test
    fun `playbackProgress should update as reviewProgress advances during playback`() = runTest {
        val draftId = "draft-advancing"
        val mockArtifact = mockk<com.saurabh.artifact.model.Artifact>(relaxed = true) {
            every { id } returns draftId
        }
        val currentArtifactFlow = MutableStateFlow<com.saurabh.artifact.model.Artifact?>(mockArtifact)
        val reviewProgressFlow = MutableStateFlow(
            com.saurabh.artifact.audio.ReviewState(
                artifactId = draftId,
                durationMs = 20000L,
                furthestPositionMs = 2000L // 10%
            )
        )
        every { audioPlayer.currentArtifact } returns currentArtifactFlow
        every { audioPlayer.reviewProgress } returns reviewProgressFlow

        val vm = DraftListViewModel(
            recordingRepository,
            draftRepository,
            publishingOrchestrator,
            authRepository,
            cleanupManager,
            audioPlayer
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.playbackProgress.collect()
        }

        org.junit.Assert.assertEquals(0.1f, vm.playbackProgress.value, 0.001f)

        // Advance to 50%
        reviewProgressFlow.value = com.saurabh.artifact.audio.ReviewState(
            artifactId = draftId,
            durationMs = 20000L,
            furthestPositionMs = 10000L
        )
        org.junit.Assert.assertEquals(0.5f, vm.playbackProgress.value, 0.001f)

        // Advance to 95% (qualifying review threshold)
        reviewProgressFlow.value = com.saurabh.artifact.audio.ReviewState(
            artifactId = draftId,
            durationMs = 20000L,
            furthestPositionMs = 19000L
        )
        org.junit.Assert.assertEquals(0.95f, vm.playbackProgress.value, 0.001f)
    }

    @Test
    fun `playbackProgress should reset to 0f when playback stops`() = runTest {
        val draftId = "draft-stopping"
        val mockArtifact = mockk<com.saurabh.artifact.model.Artifact>(relaxed = true) {
            every { id } returns draftId
        }
        val currentArtifactFlow = MutableStateFlow<com.saurabh.artifact.model.Artifact?>(mockArtifact)
        val reviewProgressFlow = MutableStateFlow(
            com.saurabh.artifact.audio.ReviewState(
                artifactId = draftId,
                durationMs = 10000L,
                furthestPositionMs = 6000L
            )
        )
        every { audioPlayer.currentArtifact } returns currentArtifactFlow
        every { audioPlayer.reviewProgress } returns reviewProgressFlow

        val vm = DraftListViewModel(
            recordingRepository,
            draftRepository,
            publishingOrchestrator,
            authRepository,
            cleanupManager,
            audioPlayer
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.playbackProgress.collect()
        }

        org.junit.Assert.assertEquals(0.6f, vm.playbackProgress.value, 0.001f)

        // Stop playback: currentArtifact becomes null, reviewProgress finalized
        currentArtifactFlow.value = null
        reviewProgressFlow.value = com.saurabh.artifact.audio.ReviewState()

        org.junit.Assert.assertEquals(0f, vm.playbackProgress.value, 0.001f)
    }

    @Test
    fun `playbackProgress should isolate and change appropriately when another draft starts`() = runTest {
        val draft1 = "draft-1"
        val draft2 = "draft-2"
        val mockArtifact1 = mockk<com.saurabh.artifact.model.Artifact>(relaxed = true) {
            every { id } returns draft1
        }
        val mockArtifact2 = mockk<com.saurabh.artifact.model.Artifact>(relaxed = true) {
            every { id } returns draft2
        }

        val currentArtifactFlow = MutableStateFlow<com.saurabh.artifact.model.Artifact?>(mockArtifact1)
        val reviewProgressFlow = MutableStateFlow(
            com.saurabh.artifact.audio.ReviewState(
                artifactId = draft1,
                durationMs = 10000L,
                furthestPositionMs = 8000L // 80%
            )
        )
        every { audioPlayer.currentArtifact } returns currentArtifactFlow
        every { audioPlayer.reviewProgress } returns reviewProgressFlow

        val vm = DraftListViewModel(
            recordingRepository,
            draftRepository,
            publishingOrchestrator,
            authRepository,
            cleanupManager,
            audioPlayer
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.playbackProgress.collect()
        }

        org.junit.Assert.assertEquals(0.8f, vm.playbackProgress.value, 0.001f)

        // Switch to draft 2
        currentArtifactFlow.value = mockArtifact2
        reviewProgressFlow.value = com.saurabh.artifact.audio.ReviewState(
            artifactId = draft2,
            durationMs = 20000L,
            furthestPositionMs = 4000L // 20%
        )

        org.junit.Assert.assertEquals(0.2f, vm.playbackProgress.value, 0.001f)
    }
}
