package com.saurabh.artifact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.ObserveCurrentUserProfileUseCase
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.OptIn

sealed class AppStartupState {
    object Initializing : AppStartupState()
    object Unauthenticated : AppStartupState()
    object Registering : AppStartupState()
    object Recovering : AppStartupState()
    object Rescue : AppStartupState()
    data class Ready(val startDestination: Any) : AppStartupState()
    data class Error(val message: String) : AppStartupState()
}

@UnstableApi
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val getInitialDestinationUseCase: GetInitialDestinationUseCase,
    private val registrationCoordinator: com.saurabh.artifact.domain.auth.RegistrationCoordinator,
    @get:UnstableApi private val logoutCoordinator: com.saurabh.artifact.domain.auth.LogoutCoordinator,
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

    private val _navigationEvent = Channel<Any>(capacity = Channel.BUFFERED)
    val navigationEvent = _navigationEvent.receiveAsFlow()

    private var pendingStartupEvent: Any? = null

    private var isStarted = false

    init {
        // Root Auth State Observation
        // If the user signs out anywhere, we detect the transition and force a full cleanup
        // before resetting to the Login screen. This handles remote logout, token revocation, etc.
        viewModelScope.launch {
            authRepository.currentUser
                .scan(null to null) { acc: Pair<com.google.firebase.auth.FirebaseUser?, com.google.firebase.auth.FirebaseUser?>, value ->
                    acc.second to value
                }
                .collect { (previous, current) ->
                    val isTransitionToUnauthenticated = previous != null && current == null
                    
                    if (isTransitionToUnauthenticated && isStarted && _startupState.value !is AppStartupState.Initializing) {
                        android.util.Log.i("LogoutHardening", "Root observer detected unauthenticated transition. Initiating cleanup.")
                        
                        try {
                            // BLOCKING: Ensure cleanup completes before UI transition to prevent stale data visibility
                            val result = logoutCoordinator.performFullCleanup()
                            android.util.Log.i("LogoutHardening", "Session cleanup completed.")
                        } catch (e: Exception) {
                            // Security: Never log sensitive data (UID, email, tokens) on failure
                            android.util.Log.e("LogoutHardening", "Session cleanup failed with an unexpected error.")
                        } finally {
                            // Hard Reset: Ensure navigation to Login screen regardless of cleanup success
                            _startupState.value = AppStartupState.Ready(Login)
                        }
                    }
                }
        }

        // Single observation of terminal startup errors
        startupCoordinator.terminalError
            .filterNotNull()
            .onEach { error ->
                android.util.Log.e("AppStartup", "Terminal failure detected.")
                _startupState.value = AppStartupState.Error("Security initialization failed.")
            }
            .launchIn(viewModelScope)
    }

    /**
     * Executes the session-aware startup sequence.
     * Determines the initial route BEFORE the UI is allowed to transition from the Splash Screen.
     */
    fun start() {
        if (isStarted) return
        isStarted = true
        android.util.Log.d("APP_FLOW", "STARTUP_BEGIN")

        if (startupCoordinator.isRescueModeActive) {
            _startupState.value = AppStartupState.Rescue
            return
        }

        executeStartup()
    }

    fun retryStartup() {
        android.util.Log.i("AppStartup", "Retrying startup...")
        _startupState.value = AppStartupState.Initializing
        startupCoordinator.reset()
        executeStartup()
    }

    private fun executeStartup() {
        viewModelScope.launch {
            try {
                startupCoordinator.start()
                
                // BLOCKING: Wait for Core/Security readiness (including App Check)
                startupCoordinator.awaitComponent(com.saurabh.artifact.startup.StartupComponent.CORE)
                
                // Only proceed if no terminal error occurred
                if (_startupState.value !is AppStartupState.Error) {
                    determineInitialRoute()
                }
            } catch (e: Exception) {
                android.util.Log.e("AppStartup", "Critical failure during startup sequence.")
                _startupState.value = AppStartupState.Error("An unexpected error occurred during startup.")
                startupCoordinator.completeAll()
            }
        }
    }

    private suspend fun determineInitialRoute() {
        android.util.Log.d("APP_FLOW", "AUTH_CHECK_BEGIN")
        val initialDestination = getInitialDestinationUseCase()
        android.util.Log.d("APP_FLOW", "AUTH_CHECK_SUCCESS: $initialDestination")

        val destination: Any = when (initialDestination) {
            InitialDestination.ONBOARDING -> Onboarding
            InitialDestination.UNAUTHENTICATED -> Login
            InitialDestination.AUTHENTICATED -> {
                // REGISTRATION GATE
                android.util.Log.d("APP_FLOW", "PROFILE_CHECK_BEGIN")
                _startupState.value = AppStartupState.Registering
                val result = try {
                    registrationCoordinator.ensureProfileExists()
                } catch (e: Exception) {
                    android.util.Log.e("AppStartup", "PROFILE_CHECK_FAILED")
                    RegistrationResult.Failure(e)
                }
                
                when (result) {
                    is RegistrationResult.SuccessExistingUser -> {
                        android.util.Log.d("APP_FLOW", "PROFILE_CHECK_SUCCESS: Existing User")
                        Home
                    }
                    is RegistrationResult.SuccessNewUser -> {
                        android.util.Log.d("APP_FLOW", "PROFILE_CHECK_SUCCESS: New User")
                        IdentityReveal
                    }
                    is RegistrationResult.Failure -> {
                        android.util.Log.e("AppStartup", "PROFILE_CHECK_FAILED")
                        _startupState.value = AppStartupState.Error("Failed to initialize profile. Please check your connection and try again.")
                        startupCoordinator.completeAll()
                        return
                    }
                }
            }
        }

        // SIGNAL: Auth and Profile are ready
        startupCoordinator.emitReadiness(com.saurabh.artifact.startup.StartupComponent.AUTH)
        startupCoordinator.emitReadiness(com.saurabh.artifact.startup.StartupComponent.DATABASE)

        _startupState.value = AppStartupState.Ready(destination)
        android.util.Log.d("APP_FLOW", "STARTUP_READY")

        // Delivery phase: Only deliver startup events to authenticated users
        if (destination !is Login && destination !is Onboarding) {
            pendingStartupEvent?.let {
                android.util.Log.i("Navigation", "Delivering pending startup event.")
                emitNavigationEvent(it)
                pendingStartupEvent = null
            }
        } else {
            // Clear pending event if we are stuck at Auth screens
            if (pendingStartupEvent != null) {
                android.util.Log.w("AuthGuard", "Pending startup event dropped.")
                pendingStartupEvent = null
            }
        }

        StartupMetrics.onAuthReady()
    }

    fun onLaunchIntent(intent: android.content.Intent?) {
        val event = parseIntent(intent) ?: return

        if (_startupState.value is AppStartupState.Ready) {
            // Immediate delivery if already ready, respecting Auth Guard
            if (authRepository.currentUser.value != null) {
                android.util.Log.d("Navigation", "App is Ready. Emitting intent event immediately.")
                emitNavigationEvent(event)
            } else {
                android.util.Log.w("AuthGuard", "Intent blocked: User is unauthenticated while app is ready.")
            }
        } else {
            // Buffer for delivery after initialization completes
            android.util.Log.i("Navigation", "App initializing. Buffering startup event: $event")
            pendingStartupEvent = event
        }
    }

    private fun parseIntent(intent: android.content.Intent?): Any? {
        if (intent == null) return null

        // 1. Handle Recording Shortcut
        if (intent.getBooleanExtra("navigate_to_recording", false)) {
            return InstantRecord()
        }

        // 2. Resolve Artifact ID from Notification Extras (Higher precedence)
        val directId = intent.getStringExtra("artifactId")
        if (directId != null && directId.isNotBlank()) {
            return IncomingArtifact(directId)
        }

        // 3. Handle Action View (App Links / URIs)
        if (intent.action == android.content.Intent.ACTION_VIEW) {
            val data: android.net.Uri? = intent.data
            if (data != null && (data.scheme == "http" || data.scheme == "https")) {
                // Canonical Pattern: /a/{artifactId}
                val pathSegments = data.pathSegments
                if (pathSegments.size >= 2 && pathSegments[0] == "a") {
                    val artifactId = pathSegments[1]
                    if (artifactId.isNotBlank()) {
                        return IncomingArtifact(artifactId)
                    }
                }
            }
        }

        return null
    }

    private fun emitNavigationEvent(event: Any) {
        viewModelScope.launch {
            _navigationEvent.send(event)
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
