package com.saurabh.artifact.ui.login

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.auth.CredentialHelper
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SessionGateResult
import com.saurabh.artifact.domain.auth.RegistrationCoordinator
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val registrationCoordinator: RegistrationCoordinator,
    val credentialHelper: CredentialHelper,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    fun signInWithGoogle(idToken: String) {
        diagnosticLogger.info(DiagnosticCategory.AUTH, "GOOGLE_SIGN_IN_STARTED")
        _loginState.value = LoginState.Loading
        viewModelScope.launch {
            authRepository.signInWithGoogle(idToken)
                .onSuccess { firebaseUser ->
                    if (firebaseUser != null) {
                        diagnosticLogger.info(DiagnosticCategory.AUTH, "GOOGLE_SIGN_IN_SUCCESS")
                        completeLoginWithSessionGate()
                    } else {
                        diagnosticLogger.error(DiagnosticCategory.AUTH, "GOOGLE_SIGN_IN_FAILED", mapOf("reason" to "FirebaseUser is null"))
                        _loginState.value = LoginState.Error(UiText.DynamicString("Google Sign-In failed: User is null"))
                    }
                }
                .onFailure { e ->
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "GOOGLE_SIGN_IN_FAILED", throwable = e)
                    _loginState.value = LoginState.Error(ErrorMessageMapper.map(e))
                }
        }
    }

    fun retrySessionGate() {
        if (authRepository.currentUser.value != null) {
            _loginState.value = LoginState.Loading
            viewModelScope.launch {
                completeLoginWithSessionGate()
            }
        }
    }

    private suspend fun completeLoginWithSessionGate() {
        val result = registrationCoordinator.ensureProfileExists()
        when (result) {
            is RegistrationResult.SuccessNewUser,
            is RegistrationResult.SuccessExistingUser -> {
                val deviceName = Build.MODEL ?: "Android Device"
                when (val gateResult = authRepository.resolveSessionGate(deviceName)) {
                    is SessionGateResult.Approved -> {
                        diagnosticLogger.info(
                            DiagnosticCategory.AUTH,
                            "LOGIN_SESSION_GATE_APPROVED",
                            mapOf("user_type" to if (result is RegistrationResult.SuccessNewUser) "NEW" else "EXISTING")
                        )
                        _loginState.value = LoginState.Success(result)
                    }
                    is SessionGateResult.Conflict -> {
                        diagnosticLogger.warn(
                            DiagnosticCategory.AUTH,
                            "LOGIN_SESSION_GATE_CONFLICT",
                            mapOf("activeDevice" to gateResult.activeDeviceName)
                        )
                        _loginState.value = LoginState.Conflict(gateResult.activeDeviceName)
                    }
                    is SessionGateResult.Failure -> {
                        diagnosticLogger.error(
                            DiagnosticCategory.AUTH,
                            "LOGIN_SESSION_GATE_FAILED",
                            throwable = gateResult.error
                        )
                        _loginState.value = LoginState.Error(ErrorMessageMapper.map(gateResult.error))
                    }
                }
            }
            is RegistrationResult.Failure -> {
                diagnosticLogger.error(DiagnosticCategory.AUTH, "PROFILE_REGISTRATION_FAILED", throwable = result.exception)
                _loginState.value = LoginState.Error(ErrorMessageMapper.map(result.exception))
            }
        }
    }

    fun transferActiveSession() {
        if (authRepository.currentUser.value != null) {
            _loginState.value = LoginState.Loading
            viewModelScope.launch {
                val deviceName = Build.MODEL ?: "Android Device"
                val correlationId = UUID.randomUUID().toString()
                authRepository.transferActiveSession(deviceName, correlationId)
                    .onSuccess {
                        completeLoginWithSessionGate()
                    }
                    .onFailure { e ->
                        diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGIN_TRANSFER_SESSION_FAILED", throwable = e)
                        _loginState.value = LoginState.Error(ErrorMessageMapper.map(e))
                    }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _loginState.value = LoginState.Idle
        }
    }

    fun onError(message: String) {
        _loginState.value = LoginState.Error(ErrorMessageMapper.map(message))
    }
}

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    data class Success(val result: RegistrationResult) : LoginState()
    data class Conflict(val activeDeviceName: String) : LoginState()
    data class Error(val message: UiText) : LoginState()
}
