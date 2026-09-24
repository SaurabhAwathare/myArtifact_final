package com.saurabh.artifact.ui.settings

import android.content.Context
import android.net.Uri
import com.saurabh.artifact.auth.CredentialHelper
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.security.ExportProgress
import com.saurabh.artifact.security.ExportService
import com.saurabh.artifact.util.ClipboardGuard
import com.saurabh.artifact.model.UserSettings
import com.saurabh.artifact.repository.DraftRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val repository = mockk<SettingsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val sessionManager = mockk<UserSessionManager>(relaxed = true)
    private val clipboardGuard = mockk<ClipboardGuard>(relaxed = true)
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    private val draftRepository = mockk<DraftRepository>(relaxed = true)
    private val credentialHelper = mockk<CredentialHelper>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val uri = mockk<Uri>(relaxed = true)

    private lateinit var viewModel: SettingsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ExportService)
        every { ExportService.exportState } returns MutableStateFlow<ExportProgress?>(null)
        every { ExportService.start(any(), any()) } just runs
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { authRepository.privateSettings } returns MutableStateFlow(null)
        every { repository.userSettings } returns MutableStateFlow(UserSettings())
        every { draftRepository.observeDrafts() } returns MutableStateFlow(emptyList())

        viewModel = SettingsViewModel(
            repository, authRepository, sessionManager,
            clipboardGuard, logoutCoordinator, draftRepository, credentialHelper, diagnosticLogger
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `unfinishedDraftCount calculates non-published draft count correctly`() = runTest {
        val draft1 = mockk<ArtifactDraftEntity> {
            every { lifecycle } returns ArtifactLifecycle.REVIEW_REQUIRED
        }
        val draft2 = mockk<ArtifactDraftEntity> {
            every { lifecycle } returns ArtifactLifecycle.PUBLISHED
        }
        every { draftRepository.observeDrafts() } returns MutableStateFlow(listOf(draft1, draft2))

        val vm = SettingsViewModel(
            repository, authRepository, sessionManager,
            clipboardGuard, logoutCoordinator, draftRepository, credentialHelper, diagnosticLogger
        )

        assertEquals(1, vm.unfinishedDraftCount.first())
    }

    @Test
    fun `isDeletionConfirmed should be true only when input is DELETE`() = runTest {
        viewModel.updateDeletionConfirmation("DELET")
        assertFalse(viewModel.isDeletionConfirmed.first())

        viewModel.updateDeletionConfirmation("DELETE")
        assertTrue(viewModel.isDeletionConfirmed.first())

        viewModel.updateDeletionConfirmation("delete")
        assertFalse(viewModel.isDeletionConfirmed.first())
    }

    @Test
    fun `exportData should emit ExportStarted event`() = runTest {
        var event: SettingsUiEvent? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event = it }
        }
        viewModel.exportData(context, uri)
        assertTrue(event is SettingsUiEvent.ExportStarted)
        job.cancel()
    }

    @Test
    fun `updateNotifications should call repository`() = runTest {
        viewModel.updateNotifications(true)
        coVerify { repository.updateSettings(match { it.notificationsEnabled }) }
    }

    @Test
    fun `updateSmartReminders should call repository`() = runTest {
        viewModel.updateSmartReminders(true)
        coVerify { repository.updateSettings(match { it.smartRemindersEnabled }) }
    }

    @Test
    fun `updateStealthMode should call repository`() = runTest {
        viewModel.updateStealthMode(true)
        coVerify { repository.updateSettings(match { it.stealthModeEnabled }) }
    }

    @Test
    fun `updateDataCollection should call repository`() = runTest {
        viewModel.updateDataCollection(true)
        coVerify { repository.updateSettings(match { it.dataCollectionConsent }) }
    }
}
