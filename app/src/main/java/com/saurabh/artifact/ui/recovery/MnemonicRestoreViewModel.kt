package com.saurabh.artifact.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RecoveryUiState {
    object Idle : RecoveryUiState()
    object Processing : RecoveryUiState()
    data class Error(val message: UiText) : RecoveryUiState()
    object Success : RecoveryUiState()
}

@HiltViewModel
class MnemonicRestoreViewModel @Inject constructor(
    private val encryptionManager: DatabaseEncryptionManager,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    private val _mnemonic = MutableStateFlow("")
    val mnemonic: StateFlow<String> = _mnemonic.asStateFlow()

    private val _uiState = MutableStateFlow<RecoveryUiState>(RecoveryUiState.Idle)
    val uiState: StateFlow<RecoveryUiState> = _uiState.asStateFlow()

    fun onMnemonicChange(value: String) {
        _mnemonic.value = value
        if (_uiState.value is RecoveryUiState.Error) {
            _uiState.value = RecoveryUiState.Idle
        }
    }

    fun attemptRecovery(onSuccess: () -> Unit) {
        val phrase = _mnemonic.value.trim()
        if (phrase.isBlank()) return

        viewModelScope.launch {
            _uiState.value = RecoveryUiState.Processing
            
            encryptionManager.tryRecovery(phrase)
                .onSuccess {
                    _uiState.value = RecoveryUiState.Success
                    diagnosticLogger.info(DiagnosticCategory.SECURITY, "UI_RECOVERY_SUCCESS")
                    onSuccess()
                }
                .onFailure { e ->
                    diagnosticLogger.error(DiagnosticCategory.SECURITY, "UI_RECOVERY_FAILED", throwable = e)
                    _uiState.value = RecoveryUiState.Error(ErrorMessageMapper.map(e))
                }
        }
    }

    fun startFresh(onConfirm: () -> Unit) {
        viewModelScope.launch {
            encryptionManager.generateAndStoreNewPassphrase()
            diagnosticLogger.warn(DiagnosticCategory.SECURITY, "USER_CHOSE_START_FRESH")
            onConfirm()
        }
    }
}
