package com.saurabh.artifact.ui.login

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.firebase.auth.AuthCredential
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: com.saurabh.artifact.repository.UserRepository,
    val googleSignInClient: GoogleSignInClient
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    fun signInWithGoogle(idToken: String) {
        _loginState.value = LoginState.Loading
        viewModelScope.launch {
            authRepository.signInWithGoogle(idToken)
                .onSuccess { firebaseUser ->
                    if (firebaseUser != null) {
                        try {
                            userRepository.getOrCreateProfile()
                            _loginState.value = LoginState.Success
                        } catch (e: Exception) {
                            Log.e("AUTH", "Profile creation failed", e)
                            _loginState.value = LoginState.Error(ErrorMessageMapper.map(e))
                        }
                    } else {
                        _loginState.value = LoginState.Error(UiText.DynamicString("Google Sign-In failed: User is null"))
                    }
                }
                .onFailure { e ->
                    Log.e("AUTH", "Google Sign-In failed", e)
                    _loginState.value = LoginState.Error(ErrorMessageMapper.map(e))
                }
        }
    }

    fun onError(message: String) {
        _loginState.value = LoginState.Error(ErrorMessageMapper.map(message))
    }
}

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: UiText) : LoginState()
}
