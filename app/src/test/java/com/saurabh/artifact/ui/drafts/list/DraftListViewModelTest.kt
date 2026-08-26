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
}
