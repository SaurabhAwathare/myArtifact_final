package com.saurabh.artifact.ui.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.domain.UsernameValidator
import com.saurabh.artifact.model.UsernameUiState
import com.saurabh.artifact.model.UsernameValidationResult
import com.saurabh.artifact.model.ValidationReason
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.util.UsernameGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UsernameViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val auth: com.google.firebase.auth.FirebaseAuth,
    private val validator: UsernameValidator
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsernameUiState())
    val uiState: StateFlow<UsernameUiState> = _uiState.asStateFlow()

    private var validationJob: Job? = null

    init {
        generateInitialSuggestions()
    }

    private fun generateInitialSuggestions() {
        val suggestions = UsernameGenerator.generateSuggestions(4, "Reflective")
        _uiState.update { it.copy(suggestions = suggestions) }
    }

    fun onUsernameChanged(newUsername: String) {
        _uiState.update { it.copy(
            username = newUsername,
            isValidating = true,
            isAvailable = null,
            validationResult = null
        ) }
        
        validationJob?.cancel()
        validationJob = viewModelScope.launch {
            delay(500) // Debounce
            performValidation(newUsername)
        }
    }

    private suspend fun performValidation(username: String) {
        if (username.isBlank()) {
            _uiState.update { it.copy(isValidating = false) }
            return
        }

        val realName = auth.currentUser?.displayName
        val email = auth.currentUser?.email

        // Layer 1-4: Local Moderation
        val localResult = validator.validate(username, realName, email)
        
        if (!localResult.isValid) {
            _uiState.update { it.copy(
                isValidating = false,
                validationResult = localResult,
                isAvailable = false
            ) }
            return
        }

        // Layer 5: Availability Check
        _uiState.update { it.copy(isValidating = true) }
        val isAvailable = userRepository.isUsernameAvailable(username)
        
        val finalResult = if (isAvailable) {
            localResult.copy(isValid = true)
        } else {
            localResult.copy(isValid = false, reason = ValidationReason.ALREADY_TAKEN)
        }

        _uiState.update { it.copy(
            isValidating = false,
            validationResult = finalResult,
            isAvailable = isAvailable
        ) }
    }

    fun selectSuggestion(suggestion: String) {
        onUsernameChanged(suggestion)
    }

    fun refreshSuggestions(theme: String) {
        val suggestions = UsernameGenerator.generateSuggestions(4, theme)
        _uiState.update { it.copy(suggestions = suggestions) }
    }
}
