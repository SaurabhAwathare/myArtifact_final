package com.saurabh.artifact.ui.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.repository.UserProfileManager
import com.saurabh.artifact.util.UsernameGenerator
import com.saurabh.artifact.model.*
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class UsernameAvailability {
    CHECKING, AVAILABLE, TAKEN, ERROR, NONE
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val userProfileManager: UserProfileManager,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val userRepository: com.saurabh.artifact.repository.UserRepository,
) : ViewModel() {

    private val _avatarConfig = MutableStateFlow(AvatarConfig())
    val avatarConfig: StateFlow<AvatarConfig> = _avatarConfig.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _usernameError = MutableStateFlow<String?>(null)
    val usernameError: StateFlow<String?> = _usernameError.asStateFlow()

    private val _availability = MutableStateFlow(UsernameAvailability.NONE)
    val availability: StateFlow<UsernameAvailability> = _availability.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    val userProfile = authRepository.currentUser.flatMapLatest { user ->
        if (user != null) userRepository.streamUserProfile(user.uid)
        else flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val cooldownDays: StateFlow<Int> = userProfile.map { profile ->
        userProfileManager.getUsernameCooldownDays(profile)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var availabilityCheckJob: Job? = null

    val isUsernameValid = combine(_username, _usernameError, _availability, cooldownDays) { name, error, availability, cooldown ->
        (name.isNotEmpty() && error == null && availability == UsernameAvailability.AVAILABLE && cooldown == 0)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    init {
        viewModelScope.launch {
            userProfileManager.activeAvatarConfig.collectLatest { config ->
                _avatarConfig.value = config
            }
        }
        viewModelScope.launch {
            userProfileManager.activeUsername.collectLatest { name ->
                _username.value = name
                validateUsername(name)
            }
        }
        
        // Live validation and suggestions
        viewModelScope.launch {
            _username.collectLatest { name ->
                validateUsername(name)
                updateSuggestions(name)
                checkAvailability(name)
            }
        }
    }

    private fun validateUsername(name: String) {
        _usernameError.value = UsernameGenerator.validate(name)
    }

    private fun checkAvailability(name: String) {
        availabilityCheckJob?.cancel()
        
        if (name.isEmpty() || _usernameError.value != null) {
            _availability.value = UsernameAvailability.NONE
            return
        }

        availabilityCheckJob = viewModelScope.launch {
            _availability.value = UsernameAvailability.CHECKING
            delay(500) // Debounce
            
            try {
                val isAvailable = userProfileManager.isUsernameAvailable(name)
                _availability.value = if (isAvailable) {
                    UsernameAvailability.AVAILABLE
                } else {
                    UsernameAvailability.TAKEN
                }
            } catch (_: Exception) {
                _availability.value = UsernameAvailability.ERROR
            }
        }
    }

    private fun updateSuggestions(name: String) {
        if (name.length >= 3) {
            _suggestions.value = UsernameGenerator.generateSuggestionsForBase(name, 4)
        } else if (name.isEmpty()) {
            _suggestions.value = UsernameGenerator.generateSuggestions(4)
        } else {
            _suggestions.value = emptyList()
        }
    }

    fun randomizePresence() {
        _avatarConfig.value = _avatarConfig.value.copy(seed = java.util.UUID.randomUUID().toString())
    }

    fun onUsernameChange(name: String) {
        _username.value = name
    }

    fun selectSuggestion(suggestion: String) {
        _username.value = suggestion
    }

    private val _uiState = MutableStateFlow<IdentityUiState>(IdentityUiState.Idle)
    val uiState: StateFlow<IdentityUiState> = _uiState.asStateFlow()

    val usernameUiState: StateFlow<UsernameUiState> = combine(
        _username,
        _availability,
        _usernameError,
        _suggestions,
        _uiState
    ) { name, avail, error, suggs, uiState ->
        UsernameUiState(
            username = name,
            isValidating = avail == UsernameAvailability.CHECKING,
            isAvailable = when (avail) {
                UsernameAvailability.AVAILABLE -> true
                UsernameAvailability.TAKEN -> false
                else -> null
            },
            validationResult = UsernameValidationResult(
                isValid = error == null && (avail == UsernameAvailability.AVAILABLE || avail == UsernameAvailability.NONE),
                reason = when {
                    error?.contains("at least 3") == true -> ValidationReason.TOO_SHORT
                    error?.contains("20 characters") == true -> ValidationReason.TOO_LONG
                    error?.contains("Only lowercase") == true -> ValidationReason.INVALID_CHARACTERS
                    avail == UsernameAvailability.TAKEN -> ValidationReason.ALREADY_TAKEN
                    else -> null
                }
            ),
            suggestions = suggs,
            isProcessing = uiState is IdentityUiState.Loading
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UsernameUiState()
    )

    fun saveIdentity(onSuccess: () -> Unit) {
        val name = _username.value
        if (!UsernameGenerator.isValid(name)) return
        if (cooldownDays.value > 0) return

        viewModelScope.launch {
            _uiState.value = IdentityUiState.Loading
            
            val userId = authRepository.currentUser.value?.uid
            if (userId != null) {
                userRepository.createUsername(userId, name)
                    .onSuccess {
                        userProfileManager.updateAvatarConfig(_avatarConfig.value)
                        onSuccess()
                    }
                    .onFailure { e ->
                        Log.e("IdentityViewModel", "Failed to save username", e)
                        val message = when(e) {
                            is AppError.UsernameTaken -> "This username is already claimed."
                            is AppError.PermissionDenied -> "Permission error. Please check your account state."
                            is AppError.NetworkFailure -> "Connection lost. Please try again."
                            else -> "Something went wrong. Technical details: ${e.message}"
                        }
                        _uiState.value = IdentityUiState.Error(message)
                    }
            } else {
                // Anonymous fallback
                userProfileManager.updateAvatarConfig(_avatarConfig.value)
                userProfileManager.updateUsername(name)
                onSuccess()
            }
        }
    }
}

sealed class IdentityUiState {
    data object Idle : IdentityUiState()
    data object Loading : IdentityUiState()
    data class Error(val message: String) : IdentityUiState()
}
