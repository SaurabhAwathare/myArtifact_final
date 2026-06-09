package com.saurabh.artifact.ui.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.repository.UserProfileManager
import com.saurabh.artifact.util.UsernameGenerator
import com.saurabh.artifact.util.SecureString
import com.saurabh.artifact.model.*
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.R
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

enum class UsernameAvailability {
    CHECKING, AVAILABLE, TAKEN, ERROR, NONE
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val userProfileManager: UserProfileManager,
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val userRepository: com.saurabh.artifact.repository.UserRepository,
    private val validator: com.saurabh.artifact.domain.UsernameValidator,
    private val auth: com.google.firebase.auth.FirebaseAuth
) : ViewModel() {

    private val _avatarConfig = MutableStateFlow(AvatarConfig())
    val avatarConfig: StateFlow<AvatarConfig> = _avatarConfig.asStateFlow()

    private val _username = MutableStateFlow("")

    private val _usernameError = MutableStateFlow<String?>(null)

    private val _availability = MutableStateFlow(UsernameAvailability.NONE)

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

    private val _validationResult = MutableStateFlow<UsernameValidationResult?>(null)

    private fun validateUsername(name: String) {
        if (name.isEmpty()) {
            _usernameError.value = null
            _validationResult.value = null
            return
        }

        val currentUser = auth.currentUser
        val realName = currentUser?.displayName?.let { SecureString.fromString(it) }
        val email = currentUser?.email?.let { SecureString.fromString(it) }
        val result = validator.validate(name, realName, email)
        
        // Clear secure strings immediately after use
        realName?.clear()
        email?.clear()
        
        _validationResult.value = result
        _usernameError.value = if (result.isValid) null else result.warnings.firstOrNull()?.message ?: "Invalid username"
    }

    private fun checkAvailability(name: String) {
        availabilityCheckJob?.cancel()
        
        if (name.isEmpty() || _usernameError.value != null) {
            _availability.value = UsernameAvailability.NONE
            return
        }

        availabilityCheckJob = viewModelScope.launch {
            _availability.value = UsernameAvailability.CHECKING
            delay(500.milliseconds) // Debounce
            
            try {
                userProfileManager.isUsernameAvailable(name)
                    .onSuccess { isAvailable ->
                        _availability.value = if (isAvailable) {
                            UsernameAvailability.AVAILABLE
                        } else {
                            UsernameAvailability.TAKEN
                        }
                    }
                    .onFailure {
                        _availability.value = UsernameAvailability.ERROR
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
        _validationResult,
        _suggestions,
        _uiState
    ) { name, avail, validation, suggs, uiState ->
        UsernameUiState(
            username = name,
            isValidating = avail == UsernameAvailability.CHECKING,
            isAvailable = when (avail) {
                UsernameAvailability.AVAILABLE -> true
                UsernameAvailability.TAKEN -> false
                else -> null
            },
            validationResult = validation?.copy(
                isValid = validation.isValid && (avail == UsernameAvailability.AVAILABLE || avail == UsernameAvailability.NONE),
                reason = if (avail == UsernameAvailability.TAKEN) ValidationReason.ALREADY_TAKEN else validation.reason
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
                        _uiState.value = IdentityUiState.Error(ErrorMessageMapper.map(e))
                    }
            } else {
                // Anonymous fallback
                userProfileManager.updateAvatarConfig(_avatarConfig.value)
                userProfileManager.updateUsername(name)
                onSuccess()
            }
        }
    }

    /**
     * Sheds the current presence and emerges with a new randomized one.
     * This is the "Identity Refresh Ritual".
     */
    fun refreshPresence(onSuccess: () -> Unit) {
        val userId = authRepository.currentUser.value?.uid ?: return
        
        viewModelScope.launch {
            _uiState.value = IdentityUiState.Loading
            userRepository.refreshAnonymousIdentity(userId)
                .onSuccess {
                    _uiState.value = IdentityUiState.Idle
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e("IdentityViewModel", "Failed to refresh presence", e)
                    _uiState.value = IdentityUiState.Error(UiText.StringResource(R.string.presence_ritual_failed))
                }
        }
    }
}

sealed class IdentityUiState {
    data object Idle : IdentityUiState()
    data object Loading : IdentityUiState()
    data class Error(val message: UiText) : IdentityUiState()
}
