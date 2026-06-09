package com.saurabh.artifact.ui.settings

import com.saurabh.artifact.security.DataExportManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.UserSettings
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
    object ExportInitiated : SettingsUiEvent()
}

data class AccountInfo(val realName: SecureString, val email: SecureString)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val dataExportManager: DataExportManager
) : ViewModel() {

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

    fun updateBiometricLock(enabled: Boolean) {
        update { it.copy(biometricLockEnabled = enabled) }
    }

    fun updateAutoLock(enabled: Boolean) {
        update { it.copy(autoLockEnabled = enabled) }
    }

    fun updateStealthMode(enabled: Boolean) {
        update { it.copy(stealthModeEnabled = enabled) }
    }

    private fun update(reducer: (UserSettings) -> UserSettings) {
        viewModelScope.launch {
            repository.updateSettings(reducer(uiState.value))
        }
    }

    fun initiateDelete() {
        viewModelScope.launch {
            _isDeleting.value = true
            val result = repository.deleteUserAccount()
            _isDeleting.value = false
            
            result.onSuccess {
                _events.emit(SettingsUiEvent.AccountDeleted)
            }.onFailure { e ->
                if (e.message?.contains("RECENT_LOGIN_REQUIRED", ignoreCase = true) == true || 
                    e.message?.contains("reauthenticate", ignoreCase = true) == true) {
                    _events.emit(SettingsUiEvent.ReauthenticationRequired)
                } else {
                    _events.emit(SettingsUiEvent.ShowMessage(ErrorMessageMapper.map(e)))
                }
            }
        }
    }

    fun reauthenticateAndRetry(idToken: String? = null) {
        viewModelScope.launch {
            _isDeleting.value = true
            val reauthenticationResult = if (idToken != null) {
                authRepository.reauthenticateWithGoogle(idToken)
            } else {
                Result.failure(Exception("Google Sign-In verification required."))
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
        viewModelScope.launch {
            repository.signOut()
                .onSuccess {
                    _events.emit(SettingsUiEvent.LoggedOut)
                }
                .onFailure {
                    _events.emit(SettingsUiEvent.ShowMessage(UiText.StringResource(R.string.logout_failed)))
                }
        }
    }

    fun exportData(outputUri: android.net.Uri) {
        viewModelScope.launch {
            dataExportManager.exportAllDrafts(outputUri)
                .onSuccess {
                    _events.emit(SettingsUiEvent.ShowMessage(UiText.StringResource(R.string.export_ready)))
                }
                .onFailure {
                    _events.emit(SettingsUiEvent.ShowMessage(UiText.StringResource(R.string.export_failed)))
                }
        }
    }
}
