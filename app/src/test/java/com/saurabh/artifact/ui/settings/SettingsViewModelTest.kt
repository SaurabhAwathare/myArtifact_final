package com.saurabh.artifact.ui.settings

import android.content.Context
import android.net.Uri
import com.saurabh.artifact.auth.CredentialHelper
import com.saurabh.artifact.data.local.ArtifactDraftEntity
import com.saurabh.artifact.data.local.UserSessionManager
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.auth.CleanupResult
import com.saurabh.artifact.domain.auth.LogoutCoordinator
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.model.User
import com.saurabh.artifact.model.UserProfile
import com.saurabh.artifact.model.UserPrivateSettings
import com.saurabh.artifact.model.UserSettings
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.security.ExportProgress
import com.saurabh.artifact.security.ExportService
import com.saurabh.artifact.util.ClipboardGuard
import com.saurabh.artifact.util.SecureString
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val userProfileFlow = MutableStateFlow(
        UserProfile(
            anonymousId = "usr_123",
            username = "Quiet Echo",
            sigil = "ECH-42",
            sigilConfig = SigilConfig(seed = "echo_seed")
        )
    )
    private val userDataFlow = MutableStateFlow<User?>(null)
    private val privateSettingsFlow = MutableStateFlow<UserPrivateSettings?>(null)

    private lateinit var viewModel: SettingsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ExportService)
        every { ExportService.exportState } returns MutableStateFlow<ExportProgress?>(null)
        every { ExportService.start(any(), any()) } just runs
        every { authRepository.currentUser } returns MutableStateFlow(null)
        every { authRepository.userData } returns userDataFlow
        every { authRepository.privateSettings } returns privateSettingsFlow
        every { sessionManager.userProfile } returns userProfileFlow
        every { sessionManager.localSessionId } returns MutableStateFlow("test_session_id")
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

    @Test
    fun `anonymous user sees their existing anonymous identity`() = runTest {
        val identityState = viewModel.anonymousIdentity
            .filterNot { it is AnonymousIdentityState.Loading }
            .first()

        assertTrue("Expected Success state but was $identityState", identityState is AnonymousIdentityState.Success)
        val success = identityState as AnonymousIdentityState.Success
        assertEquals("Quiet Echo", success.anonymousName)
        assertEquals("ECH-42", success.anonymousSigil)
        assertEquals("echo_seed", success.sigilConfig.seed)
    }

    @Test
    fun `google-linked user sees their anonymous identity and appropriate private account information`() = runTest {
        privateSettingsFlow.value = UserPrivateSettings(
            secureRealName = SecureString.fromString("Jane Doe"),
            secureEmail = SecureString.fromString("jane@example.com")
        )
        userDataFlow.value = User(
            id = "google_user_1",
            anonymousName = "Luminous Spirit",
            anonymousSigil = "LUM-09",
            sigilConfig = SigilConfig(seed = "luminous_seed")
        )

        val vm = SettingsViewModel(
            repository, authRepository, sessionManager,
            clipboardGuard, logoutCoordinator, draftRepository, credentialHelper, diagnosticLogger
        )

        val identityState = vm.anonymousIdentity
            .filterNot { it is AnonymousIdentityState.Loading }
            .first()

        assertTrue(identityState is AnonymousIdentityState.Success)
        val success = identityState as AnonymousIdentityState.Success
        assertEquals("Luminous Spirit", success.anonymousName)
        assertEquals("LUM-09", success.anonymousSigil)
        assertEquals("luminous_seed", success.sigilConfig.seed)

        val accountInfo = vm.accountInfo
            .filterNot { it == null }
            .first()

        assertNotNull(accountInfo)
        assertEquals("Jane Doe", accountInfo!!.realName.toUnsecureString())
        assertEquals("jane@example.com", accountInfo.email.toUnsecureString())
    }

    @Test
    fun `identity loading does not display fabricated profile data`() = runTest {
        every { sessionManager.userProfile } returns emptyFlow()
        every { authRepository.userData } returns MutableStateFlow(null)

        val vm = SettingsViewModel(
            repository, authRepository, sessionManager,
            clipboardGuard, logoutCoordinator, draftRepository, credentialHelper, diagnosticLogger
        )

        assertEquals(AnonymousIdentityState.Loading, vm.anonymousIdentity.value)
    }

    @Test
    fun `missing profile data has a safe fallback`() = runTest {
        userProfileFlow.value = UserProfile(
            anonymousId = "fallback_id",
            username = "",
            sigil = "",
            sigilConfig = SigilConfig(seed = "fallback")
        )
        userDataFlow.value = null

        val vm = SettingsViewModel(
            repository, authRepository, sessionManager,
            clipboardGuard, logoutCoordinator, draftRepository, credentialHelper, diagnosticLogger
        )

        val state = vm.anonymousIdentity
            .filterNot { it is AnonymousIdentityState.Loading }
            .first()

        assertTrue("Expected Fallback but was $state", state is AnonymousIdentityState.Fallback)
        val fallback = state as AnonymousIdentityState.Fallback
        assertEquals("Quiet Presence", fallback.anonymousName)
        assertNull(fallback.anonymousSigil)
        assertEquals("fallback", fallback.sigilConfig.seed)
    }

    @Test
    fun `initiateDelete emits AccountDeleted on success`() = runTest {
        coEvery { repository.deleteUserAccount() } returns Result.success(Unit)

        var event: SettingsUiEvent? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event = it }
        }

        viewModel.initiateDelete()
        assertTrue(event is SettingsUiEvent.AccountDeleted)
        job.cancel()
    }

    @Test
    fun `initiateDelete emits ReauthenticationRequired on reauth error`() = runTest {
        coEvery { repository.deleteUserAccount() } returns Result.failure(AppError.ReauthenticationRequired())

        var event: SettingsUiEvent? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event = it }
        }

        viewModel.initiateDelete()
        assertTrue(event is SettingsUiEvent.ReauthenticationRequired)
        job.cancel()
    }

    @Test
    fun `logout calls logoutCoordinator and emits LoggedOut on success`() = runTest {
        val cleanupResult = mockk<CleanupResult>(relaxed = true)
        coEvery { logoutCoordinator.executeLogout(any()) } returns Result.success(cleanupResult)

        var event: SettingsUiEvent? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event = it }
        }

        viewModel.logout()
        assertTrue(event is SettingsUiEvent.LoggedOut)
        coVerify { logoutCoordinator.executeLogout(any()) }
        job.cancel()
    }

    @Test
    fun `copyEmailToClipboard invokes clipboardGuard with correct email`() = runTest {
        privateSettingsFlow.value = UserPrivateSettings(
            secureRealName = SecureString.fromString("Jane Doe"),
            secureEmail = SecureString.fromString("jane@example.com")
        )

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.accountInfo.collect {}
        }

        viewModel.copyEmailToClipboard(context)

        verify {
            clipboardGuard.copySensitive(
                context = context,
                label = "User Email",
                text = "jane@example.com"
            )
        }
        job.cancel()
    }
}
