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
    private val identityProtectionPolicy: com.saurabh.artifact.domain.IdentityProtectionPolicy,
    private val auth: com.google.firebase.auth.FirebaseAuth
) : ViewModel() {

    private val _avatarConfig = MutableStateFlow(AvatarConfig())
    val avatarConfig: StateFlow<AvatarConfig> = _avatarConfig.asStateFlow()

    private val _username = MutableStateFlow("")

    private val _usernameError = MutableStateFlow<String?>(null)

    private val _availability = MutableStateFlow(UsernameAvailability.NONE)
    
    private val _hasUserEdited = MutableStateFlow(false)

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _validationResult = MutableStateFlow<UsernameValidationResult?>(null)
    private val _uiState = MutableStateFlow<IdentityUiState>(IdentityUiState.Idle)
    val uiState: StateFlow<IdentityUiState> = _uiState.asStateFlow()

    val userProfile = authRepository.currentUser.flatMapLatest { user ->
        if (user != null) userRepository.streamUserProfile(user.uid)
        else flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val identityMetadata = userProfile.map { it?.identityMetadata ?: com.saurabh.artifact.model.IdentityMetadata() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.saurabh.artifact.model.IdentityMetadata())

    val changeSeverity = identityMetadata.map { 
        identityProtectionPolicy.getChangeSeverity(it.identityChangeCount30Days)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.saurabh.artifact.domain.IdentityProtectionPolicy.ChangeSeverity.NORMAL)

    private var availabilityCheckJob: Job? = null

    val isUsernameValid = combine(_username, _usernameError, _availability) { name, error, availability ->
        (name.isNotEmpty() && error == null && availability == UsernameAvailability.AVAILABLE)
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

            // If the name is the user's current name, it's available to them
            if (name.equals(userProfile.value?.anonymousName, ignoreCase = true)) {
                _availability.value = UsernameAvailability.AVAILABLE
                return@launch
            }

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
        _hasUserEdited.value = true
        _username.value = name
    }

    fun selectSuggestion(suggestion: String) {
        _hasUserEdited.value = true
        _username.value = suggestion
    }

    val usernameUiState: StateFlow<UsernameUiState> = combine(
        _username,
        _availability,
        _validationResult,
        _suggestions,
        _uiState,
        _hasUserEdited
    ) { params ->
        val name = params[0] as String
        val avail = params[1] as UsernameAvailability
        val validation = params[2] as? com.saurabh.artifact.model.UsernameValidationResult
        val suggs = params[3] as List<String>
        val uiState = params[4] as IdentityUiState
        val hasEdited = params[5] as Boolean

        UsernameUiState(
            username = name,
            isValidating = avail == UsernameAvailability.CHECKING,
            isAvailable = if (hasEdited) {
                when (avail) {
                    UsernameAvailability.AVAILABLE -> true
                    UsernameAvailability.TAKEN -> false
                    else -> null
                }
            } else null,
            validationResult = if (hasEdited) {
                validation?.copy(
                    isValid = validation.isValid && (avail == UsernameAvailability.AVAILABLE || avail == UsernameAvailability.NONE),
                    reason = if (avail == UsernameAvailability.TAKEN) ValidationReason.ALREADY_TAKEN else validation.reason
                )
            } else null,
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
     * Triggers an emergency identity reset for privacy protection.
     */
    fun emergencyReset(onSuccess: () -> Unit) {
        val userId = authRepository.currentUser.value?.uid ?: return
        
        viewModelScope.launch {
            _uiState.value = IdentityUiState.Loading
            userProfileManager.emergencyIdentityReset(userId)
                .onSuccess {
                    _uiState.value = IdentityUiState.Idle
                    onSuccess()
                }
                .onFailure { e ->
                    Log.e("IdentityViewModel", "Emergency reset failed", e)
                    _uiState.value = IdentityUiState.Error(ErrorMessageMapper.map(e))
                }
        }
    }

    /**
     * Reports an identity exposure incident.
     */
    fun reportExposure(reportedUserId: String, artifactId: String?, commentId: String?) {
        val reporterId = authRepository.currentUser.value?.uid ?: return
        
        viewModelScope.launch {
            userRepository.reportIdentityExposure(reporterId, reportedUserId, artifactId, commentId)
                .onFailure { e ->
                    Log.e("IdentityViewModel", "Failed to report exposure", e)
                }
        }
    }
}

sealed class IdentityUiState {
    data object Idle : IdentityUiState()
    data object Loading : IdentityUiState()
    data class Error(val message: UiText) : IdentityUiState()
}
