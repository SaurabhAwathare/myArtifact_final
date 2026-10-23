package com.saurabh.artifact.ui.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.security.BackupEncryptionManager
import com.saurabh.artifact.security.DatabaseEncryptionManager
import com.saurabh.artifact.security.MnemonicGenerator
import com.saurabh.artifact.util.OnboardingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RecoveryStatus {
    object Completed : RecoveryStatus()
    object Incomplete : RecoveryStatus()
    object PhraseMissing : RecoveryStatus()
    data class Error(val message: String) : RecoveryStatus()
}

@HiltViewModel
class MnemonicRevealViewModel @Inject constructor(
    private val backupEncryptionManager: BackupEncryptionManager,
    private val databaseEncryptionManager: DatabaseEncryptionManager,
    private val onboardingManager: OnboardingManager,
    private val diagnosticLogger: DiagnosticLogger,
    private val authRepository: AuthRepository? = null
) : ViewModel() {

    private val _mnemonicWords = MutableStateFlow<List<String>>(emptyList())
    val mnemonicWords: StateFlow<List<String>> = _mnemonicWords.asStateFlow()

    private val _isExistingPhrase = MutableStateFlow(false)
    val isExistingPhrase: StateFlow<Boolean> = _isExistingPhrase.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    private val _recoveryStatus = MutableStateFlow<RecoveryStatus>(RecoveryStatus.Incomplete)
    val recoveryStatus: StateFlow<RecoveryStatus> = _recoveryStatus.asStateFlow()

    private val currentUid: String?
        get() = try {
            authRepository?.currentUserId?.ifEmpty { null }
        } catch (_: Exception) {
            null
        }

    init {
        initializeRecoveryState()
    }

    private fun initializeRecoveryState() {
        viewModelScope.launch {
            _isProcessing.value = true
            _setupError.value = null
            try {
                val uid = currentUid
                val isWrapperPresent = databaseEncryptionManager.isRecoverySetupForUser(uid).first()
                if (isWrapperPresent) {
                    _isExistingPhrase.value = true
                    val storedPhrase = backupEncryptionManager.getRecoveryPhrase(uid)
                    if (storedPhrase != null) {
                        val isValid = databaseEncryptionManager.validateRecoveryPhrase(uid, storedPhrase)
                        if (isValid) {
                            _mnemonicWords.value = storedPhrase.trim().split("\\s+".toRegex())
                            _recoveryStatus.value = RecoveryStatus.Completed
                            if (uid != null) onboardingManager.setMnemonicSaved(uid, true) else onboardingManager.setMnemonicSaved(true)
                        } else {
                            val err = "Stored recovery phrase is inconsistent with local database recovery setup."
                            _setupError.value = err
                            _recoveryStatus.value = RecoveryStatus.Error(err)
                        }
                    } else {
                        val err = "Recovery setup is active for this device database, but stored recovery phrase is unavailable for this account."
                        _setupError.value = err
                        _recoveryStatus.value = RecoveryStatus.PhraseMissing
                    }
                } else {
                    _isExistingPhrase.value = false
                    _mnemonicWords.value = MnemonicGenerator.generateMnemonic()
                    _recoveryStatus.value = RecoveryStatus.Incomplete
                }
            } catch (e: Exception) {
                val err = "Failed to load recovery phrase state: ${e.message}"
                _setupError.value = err
                _recoveryStatus.value = RecoveryStatus.Error(err)
                diagnosticLogger.error(DiagnosticCategory.SECURITY, "MNEMONIC_INIT_FAILED", throwable = e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun restoreExistingPhrase(inputPhrase: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isProcessing.value = true
            _setupError.value = null
            try {
                val uid = currentUid
                val isValid = databaseEncryptionManager.validateRecoveryPhrase(uid, inputPhrase)
                if (isValid) {
                    if (uid != null) {
                        backupEncryptionManager.saveMnemonic(uid, inputPhrase)
                        onboardingManager.setMnemonicSaved(uid, true)
                    } else {
                        backupEncryptionManager.saveMnemonic(inputPhrase)
                        onboardingManager.setMnemonicSaved(true)
                    }
                    _mnemonicWords.value = inputPhrase.trim().split("\\s+".toRegex())
                    _isExistingPhrase.value = true
                    _recoveryStatus.value = RecoveryStatus.Completed
                    onSuccess()
                } else {
                    val err = "Entered recovery phrase does not match local database recovery setup."
                    _setupError.value = err
                    _recoveryStatus.value = RecoveryStatus.Error(err)
                }
            } catch (e: Exception) {
                val err = "An unexpected error occurred during phrase restoration: ${e.message}"
                _setupError.value = err
                _recoveryStatus.value = RecoveryStatus.Error(err)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun rekeyAccount(newPhrase: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isProcessing.value = true
            _setupError.value = null
            try {
                val uid = currentUid
                val wrapperResult = if (uid != null) {
                    databaseEncryptionManager.createRecoveryWrapper(uid, newPhrase)
                } else {
                    databaseEncryptionManager.createRecoveryWrapper(newPhrase)
                }

                wrapperResult
                    .onSuccess {
                        if (uid != null) {
                            backupEncryptionManager.saveMnemonic(uid, newPhrase)
                            onboardingManager.setMnemonicSaved(uid, true)
                        } else {
                            backupEncryptionManager.saveMnemonic(newPhrase)
                            onboardingManager.setMnemonicSaved(true)
                        }
                        _mnemonicWords.value = newPhrase.trim().split("\\s+".toRegex())
                        _isExistingPhrase.value = true
                        _recoveryStatus.value = RecoveryStatus.Completed
                        diagnosticLogger.info(DiagnosticCategory.SECURITY, "MNEMONIC_REKEY_COMPLETED")
                        onSuccess()
                    }
                    .onFailure { e ->
                        val err = "Failed to re-key database: ${e.message}"
                        _setupError.value = err
                        _recoveryStatus.value = RecoveryStatus.Error(err)
                        diagnosticLogger.error(DiagnosticCategory.SECURITY, "MNEMONIC_REKEY_FAILED", throwable = e)
                    }
            } catch (e: Exception) {
                val err = "An unexpected error occurred during re-keying: ${e.message}"
                _setupError.value = err
                _recoveryStatus.value = RecoveryStatus.Error(err)
                diagnosticLogger.error(DiagnosticCategory.SECURITY, "MNEMONIC_REKEY_FATAL", throwable = e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun completeSetup(onSuccess: () -> Unit) {
        if (_isExistingPhrase.value) {
            if (_setupError.value == null && _mnemonicWords.value.isNotEmpty()) {
                viewModelScope.launch {
                    val uid = currentUid
                    if (uid != null) onboardingManager.setMnemonicSaved(uid, true) else onboardingManager.setMnemonicSaved(true)
                    _recoveryStatus.value = RecoveryStatus.Completed
                    onSuccess()
                }
            }
            return
        }

        val words = _mnemonicWords.value
        if (words.isEmpty()) return

        val phrase = words.joinToString(" ")
        
        viewModelScope.launch {
            _isProcessing.value = true
            _setupError.value = null
            
            try {
                val uid = currentUid
                val isWrapperPresent = databaseEncryptionManager.isRecoverySetupForUser(uid).first()
                if (isWrapperPresent) {
                    _setupError.value = "Recovery setup already exists on this database."
                    _isExistingPhrase.value = true
                    return@launch
                }

                // 1. Create the recovery wrapper for the database
                val wrapperResult = if (uid != null) {
                    databaseEncryptionManager.createRecoveryWrapper(uid, phrase)
                } else {
                    databaseEncryptionManager.createRecoveryWrapper(phrase)
                }

                wrapperResult
                    .onSuccess {
                        // 2. Save mnemonic locally (Keystore-encrypted)
                        if (uid != null) {
                            backupEncryptionManager.saveMnemonic(uid, phrase)
                            onboardingManager.setMnemonicSaved(uid, true)
                        } else {
                            backupEncryptionManager.saveMnemonic(phrase)
                            onboardingManager.setMnemonicSaved(true)
                        }
                        
                        _recoveryStatus.value = RecoveryStatus.Completed
                        diagnosticLogger.info(DiagnosticCategory.SECURITY, "MNEMONIC_SETUP_COMPLETED")
                        onSuccess()
                    }
                    .onFailure { e ->
                        val err = "Failed to secure database: ${e.message}"
                        _setupError.value = err
                        _recoveryStatus.value = RecoveryStatus.Error(err)
                        diagnosticLogger.error(DiagnosticCategory.SECURITY, "MNEMONIC_SETUP_WRAPPER_FAILED", throwable = e)
                    }
            } catch (e: Exception) {
                val err = "An unexpected error occurred during setup."
                _setupError.value = err
                _recoveryStatus.value = RecoveryStatus.Error(err)
                diagnosticLogger.error(DiagnosticCategory.SECURITY, "MNEMONIC_SETUP_FATAL", throwable = e)
            } finally {
                _isProcessing.value = false
            }
        }
    }
}
