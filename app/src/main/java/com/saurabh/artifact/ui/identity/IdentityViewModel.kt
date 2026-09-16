package com.saurabh.artifact.ui.identity

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.google.firebase.auth.FirebaseAuth
import com.saurabh.artifact.R
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.domain.IdentityProtectionPolicy
import com.saurabh.artifact.domain.UsernameValidator
import com.saurabh.artifact.model.AppError
import com.saurabh.artifact.model.IdentityMetadata
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.UserProfileManager
import com.saurabh.artifact.repository.UserRepository
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.util.SecureString
import com.saurabh.artifact.util.UsernameGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CandidateBatchUiState {
    data object Loading : CandidateBatchUiState
    data class Success(val candidates: List<String>) : CandidateBatchUiState
    data class Error(val message: UiText) : CandidateBatchUiState
}

@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val userProfileManager: UserProfileManager,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val validator: UsernameValidator,
    private val identityProtectionPolicy: IdentityProtectionPolicy,
    private val auth: FirebaseAuth,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    private val _sigilConfig = MutableStateFlow(SigilConfig())
    val sigilConfig: StateFlow<SigilConfig> = _sigilConfig.asStateFlow()

    private val _initialSigilConfig = MutableStateFlow<SigilConfig?>(null)

    val hasSigilChanged: StateFlow<Boolean> = combine(_sigilConfig, _initialSigilConfig) { current, initial ->
        initial != null && current != initial
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _candidateUiState = MutableStateFlow<CandidateBatchUiState>(CandidateBatchUiState.Loading)
    val candidateUiState: StateFlow<CandidateBatchUiState> = _candidateUiState.asStateFlow()

    private val _selectedCandidate = MutableStateFlow<String?>(null)
    val selectedCandidate: StateFlow<String?> = _selectedCandidate.asStateFlow()

    private val _uiState = MutableStateFlow<IdentityUiState>(IdentityUiState.Idle)
    val uiState: StateFlow<IdentityUiState> = _uiState.asStateFlow()

    val isSaveEnabled: StateFlow<Boolean> = combine(
        _selectedCandidate,
        hasSigilChanged,
        _uiState
    ) { candidate, sigilChanged, uiState ->
        val hasCandidateSelected = !candidate.isNullOrEmpty()
        (hasCandidateSelected || sigilChanged) && uiState !is IdentityUiState.Loading
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val userProfile = authRepository.currentUser.flatMapLatest { user ->
        if (user != null) userRepository.streamUserProfile(user.uid)
        else flowOf(null)
    }.catch { e ->
        diagnosticLogger.error(DiagnosticCategory.FIRESTORE, "USER_PROFILE_CRASH", emptyMap(), e)
        _uiState.value = IdentityUiState.Error(ErrorMessageMapper.map(e))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val identityMetadata = userProfile.map { it?.identityMetadata ?: IdentityMetadata() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IdentityMetadata())

    val changeSeverity = identityMetadata.map { 
        identityProtectionPolicy.getChangeSeverity(it.identityChangeCount30Days)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IdentityProtectionPolicy.ChangeSeverity.NORMAL)

    private var currentGenerationId = 0L
    private var candidateJob: Job? = null

    init {
        viewModelScope.launch {
            userProfileManager.activeSigilConfig.collectLatest { config ->
                if (_initialSigilConfig.value == null) {
                    _initialSigilConfig.value = config
                }
                _sigilConfig.value = config
            }
        }

        loadCandidateBatch()
    }

    fun loadCandidateBatch() {
        candidateJob?.cancel()
        val generationId = ++currentGenerationId
        _candidateUiState.value = CandidateBatchUiState.Loading

        candidateJob = viewModelScope.launch {
            authRepository.awaitAuthRestoration()

            if (generationId != currentGenerationId) return@launch

            if (auth.currentUser == null) {
                if (generationId == currentGenerationId) {
                    _candidateUiState.value = CandidateBatchUiState.Error(
                        UiText.DynamicString("Unable to load identity candidates. Please check your connection and try again.")
                    )
                }
                return@launch
            }

            val currentUser = auth.currentUser
            val realName = currentUser?.displayName?.let { SecureString.fromString(it) }
            val email = currentUser?.email?.let { SecureString.fromString(it) }

            try {
                // 1. Generate candidate over-supply (15 raw suggestions)
                val rawCandidates = UsernameGenerator.generateSuggestions(15).distinct()

                // 2. Filter through UsernameValidator (privacy + safety)
                val validCandidates = rawCandidates.filter { candidate ->
                    val validation = validator.validate(candidate, realName, email)
                    validation.isValid && !validation.hasBlockingError
                }

                // 3. Concurrent availability checks via coroutineScope
                val availableCandidates = coroutineScope {
                    validCandidates.map { candidate ->
                        async {
                            val result = userProfileManager.isUsernameAvailable(candidate)
                            if (result.getOrDefault(false)) candidate else null
                        }
                    }.awaitAll().filterNotNull()
                }

                // Guard against stale async batches
                if (generationId != currentGenerationId) return@launch

                val finalBatch = availableCandidates.distinct().take(5)
                if (finalBatch.isNotEmpty()) {
                    _candidateUiState.value = CandidateBatchUiState.Success(finalBatch)
                } else {
                    _candidateUiState.value = CandidateBatchUiState.Error(
                        UiText.DynamicString("Unable to load identity candidates. Please check your connection and try again.")
                    )
                }
            } catch (e: Exception) {
                if (generationId == currentGenerationId) {
                    _candidateUiState.value = CandidateBatchUiState.Error(
                        UiText.DynamicString("Unable to load identity candidates. Please check your connection and try again.")
                    )
                }
            } finally {
                realName?.clear()
                email?.clear()
            }
        }
    }

    fun refreshCandidates() {
        _selectedCandidate.value = null
        loadCandidateBatch()
    }

    fun selectCandidate(name: String) {
        _selectedCandidate.value = name
    }

    fun saveIdentity(onSuccess: () -> Unit) {
        val candidate = _selectedCandidate.value
        val sigilChanged = hasSigilChanged.value

        if (candidate.isNullOrEmpty() && !sigilChanged) return

        viewModelScope.launch {
            _uiState.value = IdentityUiState.Loading

            if (sigilChanged) {
                val sigilResult = userProfileManager.updateSigilConfig(_sigilConfig.value)
                if (sigilResult.isFailure && candidate.isNullOrEmpty()) {
                    val e = sigilResult.exceptionOrNull()
                    diagnosticLogger.error(DiagnosticCategory.PROFILE, "SIGIL_SAVE_FAILED", throwable = e)
                    _uiState.value = IdentityUiState.Error(ErrorMessageMapper.map(e ?: Exception("Failed to update sigil")))
                    return@launch
                }
            }

            if (!candidate.isNullOrEmpty()) {
                if (!UsernameGenerator.isValid(candidate)) {
                    _uiState.value = IdentityUiState.Idle
                    return@launch
                }

                userProfileManager.updateUsername(candidate)
                    .onSuccess {
                        _initialSigilConfig.value = _sigilConfig.value
                        _uiState.value = IdentityUiState.Idle
                        onSuccess()
                    }
                    .onFailure { e ->
                        diagnosticLogger.error(DiagnosticCategory.PROFILE, "USERNAME_SAVE_FAILED", throwable = e)
                        if (e is AppError.UsernameTaken) {
                            _selectedCandidate.value = null
                            _uiState.value = IdentityUiState.Error(
                                UiText.DynamicString("That identity was just reserved. Fresh options loaded.")
                            )
                            loadCandidateBatch()
                        } else {
                            _uiState.value = IdentityUiState.Error(ErrorMessageMapper.map(e))
                        }
                    }
            } else {
                _initialSigilConfig.value = _sigilConfig.value
                _uiState.value = IdentityUiState.Idle
                onSuccess()
            }
        }
    }
}

sealed class IdentityUiState {
    data object Idle : IdentityUiState()
    data object Loading : IdentityUiState()
    data class Error(val message: UiText) : IdentityUiState()
}
