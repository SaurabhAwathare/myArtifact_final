package com.saurabh.artifact.ui.settings

import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.security.ExportProgress
import com.saurabh.artifact.security.ExportService
import com.saurabh.artifact.util.ClipboardGuard
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.auth.CredentialHelper
import com.saurabh.artifact.model.ArtifactLifecycle
import com.saurabh.artifact.model.UserSettings
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.repository.DraftRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.util.SecureString
import com.saurabh.artifact.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SettingsUiEvent {
    data class ShowMessage(val message: UiText) : SettingsUiEvent()
    object AccountDeleted : SettingsUiEvent()
    object LoggedOut : SettingsUiEvent()
    object ReauthenticationRequired : SettingsUiEvent()
    object ExportStarted : SettingsUiEvent()
}

data class AccountInfo(val realName: SecureString, val email: SecureString)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val clipboardGuard: ClipboardGuard,
    private val logoutCoordinator: com.saurabh.artifact.domain.auth.LogoutCoordinator,
    private val draftRepository: DraftRepository,
    val credentialHelper: CredentialHelper,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    val unfinishedDraftCount: StateFlow<Int> = draftRepository.observeDrafts()
        .map { drafts -> drafts.count { it.lifecycle != ArtifactLifecycle.PUBLISHED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val isAnonymous = authRepository.currentUser.map { it?.isAnonymous ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val accountInfo = authRepository.privateSettings
        .map { it?.let { p -> AccountInfo(p.secureRealName, p.secureEmail) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val uiState: StateFlow<UserSettings> = repository.userSettings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserSettings()
        )

    private val _events = MutableSharedFlow<SettingsUiEvent>()
    val events: SharedFlow<SettingsUiEvent> = _events.asSharedFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _deletionConfirmationInput = MutableStateFlow("")
    val deletionConfirmationInput: StateFlow<String> = _deletionConfirmationInput.asStateFlow()

    val isDeletionConfirmed = _deletionConfirmationInput.map { it == "DELETE" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            ExportService.exportState.collect { progress ->
                _isExporting.value = progress != null && 
                                   progress !is ExportProgress.Complete && 
                                   progress !is ExportProgress.Failed
            }
        }
    }

    fun updateNotifications(enabled: Boolean) {
        update { it.copy(notificationsEnabled = enabled) }
    }

    fun updateDeletionConfirmation(input: String) {
        _deletionConfirmationInput.value = input
    }

    fun updateSmartReminders(enabled: Boolean) {
        update { it.copy(smartRemindersEnabled = enabled) }
    }

    fun updateStealthMode(enabled: Boolean) {
        update { it.copy(stealthModeEnabled = enabled) }
    }

    fun updateDataCollection(enabled: Boolean) {
        update { it.copy(dataCollectionConsent = enabled) }
    }

    private fun update(reducer: (UserSettings) -> UserSettings) {
        viewModelScope.launch {
            val oldSettings = uiState.value
            val newSettings = reducer(oldSettings)
            repository.updateSettings(newSettings)
            
            // Log changes
            if (oldSettings.stealthModeEnabled != newSettings.stealthModeEnabled) {
                diagnosticLogger.info(DiagnosticCategory.SETTINGS, "SETTING_CHANGED", mapOf("setting" to "stealthMode", "value" to newSettings.stealthModeEnabled))
            }
            if (oldSettings.biometricLockEnabled != newSettings.biometricLockEnabled) {
                diagnosticLogger.info(DiagnosticCategory.SETTINGS, "SETTING_CHANGED", mapOf("setting" to "biometricLock", "value" to newSettings.biometricLockEnabled))
            }
        }
    }

    fun initiateDelete() {
        if (_isDeleting.value) return
        
        viewModelScope.launch {
            _isDeleting.value = true
            val result = repository.deleteUserAccount()
            
            result.onSuccess {
                _isDeleting.value = false
                _events.emit(SettingsUiEvent.AccountDeleted)
            }.onFailure { e ->
                _isDeleting.value = false
                if (e is AppError.ReauthenticationRequired) {
                    _events.emit(SettingsUiEvent.ReauthenticationRequired)
                } else {
                    _events.emit(SettingsUiEvent.ShowMessage(ErrorMessageMapper.map(e)))
                }
            }
        }
    }

    fun reauthenticateAndRetry(idToken: String? = null) {
        if (_isDeleting.value) return

        viewModelScope.launch {
            _isDeleting.value = true
            
            val reauthenticationResult = if (idToken != null) {
                authRepository.reauthenticateWithGoogle(idToken)
            } else if (isAnonymous.value && isDeletionConfirmed.value) {
                // DURABLE SAFETY: For anonymous users, the deliberate "DELETE" typing 
                // acts as the authoritative re-verification. We also attempt a token refresh
                // to satisfy Firebase's "recent login" requirement where possible.
                authRepository.refreshSession()
            } else {
                Result.failure(Exception("Authentication credentials missing."))
            }

            reauthenticationResult.onSuccess {
                val deleteResult = repository.deleteUserAccount()
                _isDeleting.value = false
                deleteResult.onSuccess {
                    _events.emit(SettingsUiEvent.AccountDeleted)
                }.onFailure { e ->
                    _events.emit(SettingsUiEvent.ShowMessage(ErrorMessageMapper.map(e)))
                }
            }.onFailure { e ->
                _isDeleting.value = false
                _events.emit(SettingsUiEvent.ShowMessage(ErrorMessageMapper.map(e)))
            }
        }
    }

    fun logout() {
        diagnosticLogger.info(DiagnosticCategory.SETTINGS, "LOGOUT_STARTED")
        viewModelScope.launch {
            logoutCoordinator.executeLogout()
                .onSuccess {
                    diagnosticLogger.info(DiagnosticCategory.SETTINGS, "LOGOUT_COMPLETED")
                    _events.emit(SettingsUiEvent.LoggedOut)
                }
                .onFailure {
                    diagnosticLogger.error(DiagnosticCategory.SETTINGS, "LOGOUT_FAILED", throwable = it)
                    _events.emit(SettingsUiEvent.ShowMessage(UiText.StringResource(R.string.logout_failed)))
                }
        }
    }

    fun exportData(context: android.content.Context, outputUri: android.net.Uri) {
        if (_isExporting.value) return
        
        diagnosticLogger.info(DiagnosticCategory.SETTINGS, "EXPORT_TRIGGERED")
        ExportService.start(context, outputUri)
        
        viewModelScope.launch {
            _events.emit(SettingsUiEvent.ExportStarted)
        }
    }

    /**
     * Demonstrates secure copying of sensitive user data.
     */
    fun copyEmailToClipboard(context: android.content.Context) {
        val email = accountInfo.value?.email?.toUnsecureString() ?: return
        clipboardGuard.copySensitive(
            context = context,
            label = "User Email",
            text = email
        )
        viewModelScope.launch {
            _events.emit(SettingsUiEvent.ShowMessage(UiText.DynamicString("Email copied. Will clear in 60s.")))
        }
    }
}
