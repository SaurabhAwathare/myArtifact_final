package com.saurabh.artifact.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.model.UserSettings
import com.saurabh.artifact.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SettingsUiEvent {
    data class ShowMessage(val message: String) : SettingsUiEvent()
    object AccountDeleted : SettingsUiEvent()
    object LoggedOut : SettingsUiEvent()
    object ReauthRequired : SettingsUiEvent()
    object ExportInitiated : SettingsUiEvent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository
) : ViewModel() {

    val isAnonymous = authRepository.currentUser.map { it?.isAnonymous ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isAdmin = authRepository.userData.map { it?.isAdmin ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    fun updateAnonymousMode(enabled: Boolean) {
        update { it.copy(isAnonymousMode = enabled) }
    }

    fun updateNotifications(enabled: Boolean) {
        update { it.copy(notificationsEnabled = enabled) }
    }

    fun updateSmartReminders(enabled: Boolean) {
        update { it.copy(smartRemindersEnabled = enabled) }
    }

    fun updateEmotionalSafety(enabled: Boolean) {
        update { it.copy(emotionalSafetyEnabled = enabled) }
    }

    fun updateDataConsent(enabled: Boolean) {
        update { it.copy(dataCollectionConsent = enabled) }
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
                    _events.emit(SettingsUiEvent.ReauthRequired)
                } else {
                    _events.emit(SettingsUiEvent.ShowMessage("Failed to delete account: ${e.message}"))
                }
            }
        }
    }

    fun reauthenticateAndRetry(idToken: String? = null) {
        viewModelScope.launch {
            _isDeleting.value = true
            val reauthResult = if (idToken != null) {
                authRepository.reauthenticateWithGoogle(idToken)
            } else {
                Result.failure(Exception("Google Sign-In verification required."))
            }

            reauthResult.onSuccess {
                val deleteResult = repository.deleteUserAccount()
                _isDeleting.value = false
                deleteResult.onSuccess {
                    _events.emit(SettingsUiEvent.AccountDeleted)
                }.onFailure { e ->
                    _events.emit(SettingsUiEvent.ShowMessage("Final deletion failed: ${e.message}"))
                }
            }.onFailure { e ->
                _isDeleting.value = false
                _events.emit(SettingsUiEvent.ShowMessage("Reauthentication failed: ${e.message}"))
            }
        }
    }

    fun deleteAccount() {
        initiateDelete()
    }

    fun logout() {
        viewModelScope.launch {
            repository.signOut()
                .onSuccess {
                    _events.emit(SettingsUiEvent.LoggedOut)
                }
                .onFailure {
                    _events.emit(SettingsUiEvent.ShowMessage("Logout failed: ${it.message}"))
                }
        }
    }

    fun exportData() {
        viewModelScope.launch {
            repository.exportUserData()
                .onSuccess { 
                    _events.emit(SettingsUiEvent.ExportInitiated) 
                }
                .onFailure { 
                    _events.emit(SettingsUiEvent.ShowMessage("Export failed: ${it.message}")) 
                }
        }
    }
}
