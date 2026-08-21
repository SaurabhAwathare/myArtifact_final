package com.saurabh.artifact.ui.settings

import android.content.Context
import android.net.Uri
import com.saurabh.artifact.auth.CredentialHelper
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.security.DataExportManager
import com.saurabh.artifact.util.ClipboardGuard
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val repository = mockk<SettingsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val dataExportManager = mockk<DataExportManager>(relaxed = true)
    private val clipboardGuard = mockk<ClipboardGuard>(relaxed = true)
    private val logoutCoordinator = mockk<LogoutCoordinator>(relaxed = true)
    private val credentialHelper = mockk<CredentialHelper>(relaxed = true)
    private val diagnosticLogger = mockk<DiagnosticLogger>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val uri = mockk<Uri>(relaxed = true)

    private lateinit var viewModel: SettingsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { authRepository.privateSettings } returns MutableStateFlow(null)
        every { repository.userSettings } returns MutableStateFlow(mockk(relaxed = true))

        viewModel = SettingsViewModel(
            repository, authRepository, dataExportManager, 
            clipboardGuard, logoutCoordinator, credentialHelper, diagnosticLogger
        )
        @Test
    fun `isDeletionConfirmed should be true only when input is DELETE`() = runTest {
        viewModel.updateDeletionConfirmation("DELET")
        assertTrue(!viewModel.isDeletionConfirmed.value)

        viewModel.updateDeletionConfirmation("DELETE")
        assertTrue(viewModel.isDeletionConfirmed.value)

        viewModel.updateDeletionConfirmation("delete")
        assertTrue(!viewModel.isDeletionConfirmed.value)
    }
}

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        @Test
    fun `isDeletionConfirmed should be true only when input is DELETE`() = runTest {
        viewModel.updateDeletionConfirmation("DELET")
        assertTrue(!viewModel.isDeletionConfirmed.value)

        viewModel.updateDeletionConfirmation("DELETE")
        assertTrue(viewModel.isDeletionConfirmed.value)

        viewModel.updateDeletionConfirmation("delete")
        assertTrue(!viewModel.isDeletionConfirmed.value)
    }
}

    @Test
    fun `exportData should emit ExportStarted event`() = runTest {
        viewModel.exportData(context, uri)
        
        val event = viewModel.events.first()
        assertTrue(event is SettingsUiEvent.ExportStarted)
        @Test
    fun `isDeletionConfirmed should be true only when input is DELETE`() = runTest {
        viewModel.updateDeletionConfirmation("DELET")
        assertTrue(!viewModel.isDeletionConfirmed.value)

        viewModel.updateDeletionConfirmation("DELETE")
        assertTrue(viewModel.isDeletionConfirmed.value)

        viewModel.updateDeletionConfirmation("delete")
        assertTrue(!viewModel.isDeletionConfirmed.value)
    }
}
    @Test
    fun `isDeletionConfirmed should be true only when input is DELETE`() = runTest {
        viewModel.updateDeletionConfirmation("DELET")
        assertTrue(!viewModel.isDeletionConfirmed.value)

        viewModel.updateDeletionConfirmation("DELETE")
        assertTrue(viewModel.isDeletionConfirmed.value)

        viewModel.updateDeletionConfirmation("delete")
        assertTrue(!viewModel.isDeletionConfirmed.value)
    }
}
