package com.saurabh.artifact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.ObserveCurrentUserProfileUseCase
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AppStartupState {
    object Initializing : AppStartupState()
    object Rescue : AppStartupState()
    data class Ready(val startDestination: Any) : AppStartupState()
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getInitialDestinationUseCase: GetInitialDestinationUseCase,
    observeCurrentUserProfileUseCase: ObserveCurrentUserProfileUseCase,
    observeStealthModeUseCase: ObserveStealthModeUseCase,
    private val startupCoordinator: StartupCoordinator
) : ViewModel() {

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.Initializing)
    val startupState = _startupState.asStateFlow()

    private val _reportingArtifactId = MutableStateFlow<String?>(null)
    val reportingArtifactId = _reportingArtifactId.asStateFlow()

    val startupStage = startupCoordinator.stage

    val currentUserProfile = observeCurrentUserProfileUseCase()

    val isStealthModeEnabled = observeStealthModeUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _isCurrentScreenSensitive = MutableStateFlow(false)

    val isSecureFlagRequired = combine(
        isStealthModeEnabled,
        _isCurrentScreenSensitive
    ) { stealth, sensitive -> stealth || sensitive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _navigationEvent = MutableSharedFlow<Any>(replay = 0)
    val navigationEvent = _navigationEvent.asSharedFlow()

    private var isStarted = false

    /**
     * Executes the session-aware startup sequence.
     * Determines the initial route BEFORE the UI is allowed to transition from the Splash Screen.
     */
    fun start() {
        if (isStarted) return
        isStarted = true

        if (startupCoordinator.isRescueModeActive) {
            _startupState.value = AppStartupState.Rescue
            return
        }

        viewModelScope.launch {
            try {
                determineInitialRoute()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Startup error", e)
                _startupState.value = AppStartupState.Ready(Login)
            }
        }
    }

    private suspend fun determineInitialRoute() {
        val destination: Any = when (getInitialDestinationUseCase()) {
            InitialDestination.ONBOARDING -> Onboarding
            InitialDestination.HOME -> Home
            InitialDestination.LOGIN -> Login
        }

        // SIGNAL: Auth is ready, we know where to go
        startupCoordinator.emitReadiness(com.saurabh.artifact.startup.StartupComponent.AUTH)

        _startupState.value = AppStartupState.Ready(destination)
        StartupMetrics.onAuthReady()
        android.util.Log.d("AppStartup", "Startup sequence complete. Destination: $destination")
    }

    fun onNewIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("navigate_to_recording", false) == true) {
            viewModelScope.launch {
                _navigationEvent.emit(InstantRecord())
            }
        }
    }

    fun showReportSheet(artifactId: String) {
        _reportingArtifactId.value = artifactId
    }

    fun dismissReportSheet() {
        _reportingArtifactId.value = null
    }

    /**
     * Updates whether the currently visible route is considered sensitive.
     * Sensitive screens automatically trigger FLAG_SECURE regardless of global stealth mode.
     */
    fun updateSecurityStatus(route: String?) {
        val sensitiveRoutes = listOf(
            "com.saurabh.artifact.navigation.Settings",
            "com.saurabh.artifact.navigation.DraftList",
            "com.saurabh.artifact.navigation.DraftEdit",
            "com.saurabh.artifact.navigation.RecordingReview",
            "com.saurabh.artifact.navigation.PublishPreparation",
            "com.saurabh.artifact.navigation.InstantRecord",
            "com.saurabh.artifact.navigation.IdentitySelection",
            "com.saurabh.artifact.navigation.Moderation",
            "com.saurabh.artifact.navigation.DebugMenu"
        )
        // Check if current route matches any sensitive route identifiers
        _isCurrentScreenSensitive.value = route != null && sensitiveRoutes.any { route.contains(it) }
    }
}
