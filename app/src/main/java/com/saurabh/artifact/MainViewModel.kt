package com.saurabh.artifact

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.ObserveCurrentUserProfileUseCase
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val startupCoordinator: StartupCoordinator,
    private val savedStateHandle: SavedStateHandle,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    companion object {
        private const val KEY_STARTUP_COMPLETED = "startup_completed"
        private const val KEY_RESOLVED_DESTINATION_ID = "resolved_destination_id"
        private const val KEY_PENDING_EVENT_JSON = "pending_event_json"
        
        private const val ID_HOME = "HOME"
        private const val ID_LOGIN = "LOGIN"
        private const val ID_ONBOARDING = "ONBOARDING"
        private const val ID_IDENTITY_REVEAL = "IDENTITY_REVEAL"
    }

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
    private var deferredNavigationJob: Job? = null

    private var isStarted = false

    init {
        // Restore pending event from SavedState if it hasn't been consumed yet
        savedStateHandle.get<String>(KEY_PENDING_EVENT_JSON)?.let { json ->
            try {
                pendingStartupEvent = Json.decodeFromString<IncomingArtifact>(json)
                android.util.Log.i("AppStartup", "Restored pending startup event from SavedState.")
            } catch (e: Exception) {
                // Compatibility: If serialization format changed, discard the event rather than crashing
                android.util.Log.w("AppStartup", "Failed to restore pending event from SavedState.")
            }
        }

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
                        diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_STARTED")
                        
                        try {
                            // BLOCKING: Ensure cleanup completes before UI transition to prevent stale data visibility
                            val result = logoutCoordinator.performFullCleanup()
                            diagnosticLogger.info(DiagnosticCategory.AUTH, "LOGOUT_COMPLETED")
                        } catch (e: Exception) {
                            // Security: Never log sensitive data (UID, email, tokens) on failure
                            diagnosticLogger.error(DiagnosticCategory.AUTH, "LOGOUT_FAILED", throwable = e)
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
        
        // --- PROCESS DEATH RESTORATION ---
        val wasCompleted = savedStateHandle.get<Boolean>(KEY_STARTUP_COMPLETED) ?: false
        val destinationId = savedStateHandle.get<String>(KEY_RESOLVED_DESTINATION_ID)

        if (wasCompleted && destinationId != null && authRepository.currentUser.value != null) {
            val restoredDestination = mapIdToRoute(destinationId)
            if (restoredDestination != null) {
                diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_RESTORED", mapOf("destination" to destinationId))
                _startupState.value = AppStartupState.Ready(restoredDestination)
                isStarted = true
                
                // If there's a pending event (restored in init), start the observer
                if (pendingStartupEvent != null) {
                    startDeferredNavigationObserver()
                }
                return
            }
        }
        // ---------------------------------

        isStarted = true
        diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_BEGIN")

        if (startupCoordinator.isRescueModeActive) {
            _startupState.value = AppStartupState.Rescue
            return
        }

        executeStartup()
    }

    private fun mapIdToRoute(id: String): Any? {
        return when (id) {
            ID_HOME -> Home
            ID_LOGIN -> Login
            ID_ONBOARDING -> Onboarding
            ID_IDENTITY_REVEAL -> IdentityReveal
            else -> null
        }
    }

    private fun mapRouteToId(route: Any): String? {
        return when (route) {
            Home -> ID_HOME
            Login -> ID_LOGIN
            Onboarding -> ID_ONBOARDING
            IdentityReveal -> ID_IDENTITY_REVEAL
            else -> null
        }
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
        diagnosticLogger.debug(DiagnosticCategory.STARTUP, "AUTH_CHECK_BEGIN")
        val initialDestination = getInitialDestinationUseCase()
        diagnosticLogger.debug(DiagnosticCategory.STARTUP, "AUTH_CHECK_SUCCESS", mapOf("result" to initialDestination.name))

        val destination: Any = when (initialDestination) {
            InitialDestination.ONBOARDING -> Onboarding
            InitialDestination.UNAUTHENTICATED -> Login
            InitialDestination.AUTHENTICATED -> {
                // REGISTRATION GATE
                diagnosticLogger.debug(DiagnosticCategory.STARTUP, "PROFILE_CHECK_BEGIN")
                _startupState.value = AppStartupState.Registering
                val result = try {
                    registrationCoordinator.ensureProfileExists()
                } catch (e: Exception) {
                    diagnosticLogger.error(DiagnosticCategory.STARTUP, "PROFILE_CHECK_FAILED", throwable = e)
                    RegistrationResult.Failure(e)
                }
                
                when (result) {
                    is RegistrationResult.SuccessExistingUser -> {
                        diagnosticLogger.debug(DiagnosticCategory.STARTUP, "PROFILE_CHECK_SUCCESS", mapOf("user_type" to "EXISTING"))
                        Home
                    }
                    is RegistrationResult.SuccessNewUser -> {
                        diagnosticLogger.debug(DiagnosticCategory.STARTUP, "PROFILE_CHECK_SUCCESS", mapOf("user_type" to "NEW"))
                        IdentityReveal
                    }
                    is RegistrationResult.Failure -> {
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
        diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_READY", mapOf("destination" to destination.javaClass.simpleName))

        // Persist completion state for process death restoration
        mapRouteToId(destination)?.let { id ->
            savedStateHandle[KEY_STARTUP_COMPLETED] = true
            savedStateHandle[KEY_RESOLVED_DESTINATION_ID] = id
        }

        // Delivery phase: Deferred navigation observer handles this if buffered
        if (destination !is Login && destination !is Onboarding) {
            // If we are already ready and authenticated, delivery happens immediately in onLaunchIntent.
            // But if we just became ready, we might have a buffered event.
        }

        StartupMetrics.onAuthReady()
    }

    fun onLaunchIntent(intent: android.content.Intent?) {
        val event = parseIntent(intent) ?: return

        if (_startupState.value is AppStartupState.Ready && authRepository.currentUser.value != null) {
            // Immediate delivery if already ready and authenticated
            diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "DEEP_LINK_OPENED", mapOf("event" to event.javaClass.simpleName))
            emitNavigationEvent(event)
        } else {
            // Buffer for delivery after initialization and authentication completes
            diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "DEEP_LINK_BUFFERED", mapOf("event" to event.javaClass.simpleName))
            pendingStartupEvent = event
            
            // Persist pending event if it's an IncomingArtifact (lightweight enough for SavedState)
            if (event is IncomingArtifact) {
                try {
                    val json = Json.encodeToString(event)
                    savedStateHandle[KEY_PENDING_EVENT_JSON] = json
                } catch (e: Exception) {
                    android.util.Log.w("Navigation", "Failed to persist pending event to SavedState.")
                }
            }
            
            startDeferredNavigationObserver()
        }
    }

    private fun startDeferredNavigationObserver() {
        val job = deferredNavigationJob
        if (job != null && job.isActive) return
        
        deferredNavigationJob = viewModelScope.launch {
            // 1. Wait until App is Ready AND Authenticated (Home/IdentityReveal)
            // This implicitly waits for profile validation/repair performed in determineInitialRoute()
            _startupState.first { state ->
                state is AppStartupState.Ready && 
                state.startDestination !is Login && 
                state.startDestination !is Onboarding
            }

            // 2. Deliver exactly once if event exists
            pendingStartupEvent?.let { event ->
                diagnosticLogger.info(DiagnosticCategory.NAVIGATION, "DEEP_LINK_DELIVERED", mapOf("event" to event.javaClass.simpleName))
                emitNavigationEvent(event)
                pendingStartupEvent = null
                savedStateHandle.remove<String>(KEY_PENDING_EVENT_JSON)
            }
            
            // 3. Observer completes here, no long-lived collector.
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
        diagnosticLogger.debug(DiagnosticCategory.NAVIGATION, "SCREEN_ENTERED", mapOf("route" to (route ?: "null")))
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
