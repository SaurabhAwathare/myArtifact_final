package com.saurabh.artifact.ui.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.security.MnemonicGenerator
import com.saurabh.artifact.util.OnboardingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MnemonicRevealViewModel @Inject constructor(
    private val backupEncryptionManager: BackupEncryptionManager,
    private val databaseEncryptionManager: DatabaseEncryptionManager,
    private val onboardingManager: OnboardingManager,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    private val _mnemonicWords = MutableStateFlow<List<String>>(emptyList())
    val mnemonicWords: StateFlow<List<String>> = _mnemonicWords.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    init {
        // Generate mnemonic once for the session
        _mnemonicWords.value = MnemonicGenerator.generateMnemonic()
    }

    fun completeSetup(onSuccess: () -> Unit) {
        val words = _mnemonicWords.value
        if (words.isEmpty()) return

        val phrase = words.joinToString(" ")
        
        viewModelScope.launch {
            _isProcessing.value = true
            _setupError.value = null
            
            try {
                // 1. Create the recovery wrapper for the database
                databaseEncryptionManager.createRecoveryWrapper(phrase)
                    .onSuccess {
                        // 2. Save mnemonic locally (Keystore-encrypted)
                        backupEncryptionManager.saveMnemonic(phrase)
                        
                        // 3. Mark setup complete in whitelisted DataStore
                        onboardingManager.setMnemonicSaved(true)
                        
                        diagnosticLogger.info(DiagnosticCategory.SECURITY, "MNEMONIC_SETUP_COMPLETED")
                        onSuccess()
                    }
                    .onFailure { e ->
                        _setupError.value = "Failed to secure database: ${e.message}"
                        diagnosticLogger.error(DiagnosticCategory.SECURITY, "MNEMONIC_SETUP_WRAPPER_FAILED", throwable = e)
                    }
            } catch (e: Exception) {
                _setupError.value = "An unexpected error occurred during setup."
                diagnosticLogger.error(DiagnosticCategory.SECURITY, "MNEMONIC_SETUP_FATAL", throwable = e)
            } finally {
                _isProcessing.value = false
            }
        }
    }
}
