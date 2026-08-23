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
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.startup.StartupComponent
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.security.PreloadResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import androidx.annotation.OptIn

sealed class AppStartupState {
    object Initializing : AppStartupState()
    object Unauthenticated : AppStartupState()
    object Registering : AppStartupState()
    object Rescue : AppStartupState()
    object Recovery : AppStartupState()
    data class Ready(
        val startDestination: Any,
        val startupAction: Any? = null,
    ) : AppStartupState()
    data class Error(val message: String) : AppStartupState()
}

@OptIn(UnstableApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val getInitialDestinationUseCase: GetInitialDestinationUseCase,
    private val registrationCoordinator: com.saurabh.artifact.domain.auth.RegistrationCoordinator,
    private val logoutCoordinator: com.saurabh.artifact.domain.auth.LogoutCoordinator,
    private val sessionManager: com.saurabh.artifact.data.local.UserSessionManager,
    observeStealthModeUseCase: ObserveStealthModeUseCase,
    private val startupCoordinator: StartupCoordinator,
    private val savedStateHandle: SavedStateHandle,
    private val diagnosticLogger: DiagnosticLogger
) : ViewModel() {

    companion object {
        private const val KEY_STARTUP_COMPLETED = "startup_completed"
        private const val KEY_RESOLVED_DESTINATION_ID = "resolved_destination_id"
        private const val KEY_RESOLVED_UID = "resolved_uid"
        private const val KEY_PENDING_EVENT_JSON = "pending_event_json"
        
        private const val ID_HOME = "HOME"
        private const val ID_LOGIN = "LOGIN"
        private const val ID_ONBOARDING = "ONBOARDING"
        private const val ID_IDENTITY_REVEAL = "IDENTITY_REVEAL"
        private const val ID_MNEMONIC_REVEAL = "MNEMONIC_REVEAL"
    }

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.Initializing)
    val startupState = _startupState.asStateFlow()

    private val _isCleaning = MutableStateFlow(value = false)
    val isCleaning = _isCleaning.asStateFlow()

    private val _reportingArtifactId = MutableStateFlow<String?>(null)
    val reportingArtifactId = _reportingArtifactId.asStateFlow()

    val startupStage = startupCoordinator.stage

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

    private val started = AtomicBoolean(false)

    init {
        // Restore pending event from SavedState if it hasn't been consumed yet
        savedStateHandle.get<String>(KEY_PENDING_EVENT_JSON)?.let { json ->
            try {
                pendingStartupEvent = Json.decodeFromString<IncomingArtifact>(json)
                diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_EVENT_RESTORED")
            } catch (_: Exception) {
                // Compatibility: If serialization format changed, discard the event rather than crashing
                diagnosticLogger.warn(DiagnosticCategory.STARTUP, "STARTUP_EVENT_RESTORE_FAILED")
            }
        }

        // Comprehensive Auth Boundary & Cleanup Observation
        // If the UID changes (direct swap or transition to/from null), 
        // we detect if the local state belongs to a different user and trigger cleanup.
        viewModelScope.launch {
            authRepository.currentUser
                .scan(null to null) { acc: Pair<com.google.firebase.auth.FirebaseUser?, com.google.firebase.auth.FirebaseUser?>, value ->
                    acc.second to value
                }
                .collect { (previous, current) ->
                    val previousUid = previous?.uid
                    val currentUid = current?.uid
                    
                    val isUidChange = previousUid != currentUid
                    val isTransitionToUnauthenticated = (previousUid != null && currentUid == null)
                    
                    if (isUidChange && started.get() && _startupState.value !is AppStartupState.Initializing) {
                        val owningUid = sessionManager.owningUid.first()
                        
                        // DECISION: Trigger cleanup if:
                        // 1. We just logged out (A -> null)
                        // 2. We swapped users (A -> B)
                        // 3. We logged into B but DataStore still belongs to A (null -> B and dirty)
                        val isCleanupRequired = isTransitionToUnauthenticated || 
                                               (currentUid != null && owningUid != null && owningUid != currentUid)
                        
                        if (isCleanupRequired) {
                            diagnosticLogger.info(DiagnosticCategory.AUTH, "ACCOUNT_BOUNDARY_DETECTED", mapOf("from" to (previousUid ?: "null"), "to" to (currentUid ?: "null")))
                            
                            try {
                                _isCleaning.value = true
                                // BLOCKING: Ensure cleanup completes before continuing to prevent stale data visibility
                                logoutCoordinator.performFullCleanup()
                                diagnosticLogger.info(DiagnosticCategory.AUTH, "ACCOUNT_CLEANUP_COMPLETED")
                            } catch (_: Exception) {
                                diagnosticLogger.error(DiagnosticCategory.AUTH, "ACCOUNT_CLEANUP_FAILED")
                            } finally {
                                _isCleaning.value = false
                                if (currentUid == null) {
                                    // Hard Reset to Login screen only if we are now logged out
                                    _startupState.value = AppStartupState.Ready(Login)
                                }
                            }
                        }
                    }
                }
        }

        // Single observation of terminal startup errors
        startupCoordinator.terminalError
            .filterNotNull()
            .onEach { _ ->
                diagnosticLogger.error(DiagnosticCategory.STARTUP, "STARTUP_TERMINAL_FAILURE")
                _startupState.value = AppStartupState.Error("Security initialization failed.")
            }
            .launchIn(viewModelScope)
    }

    /**
     * Executes the session-aware startup sequence.
     * Determines the initial route BEFORE the UI is allowed to transition from the Splash Screen.
     */
    fun start() {
        if (started.getAndSet(true)) return
        
        // --- PROCESS DEATH RESTORATION ---
        val wasCompleted = savedStateHandle.get<Boolean>(KEY_STARTUP_COMPLETED) ?: false
        val destinationId = savedStateHandle.get<String>(KEY_RESOLVED_DESTINATION_ID)
        val resolvedUid = savedStateHandle.get<String>(KEY_RESOLVED_UID)
        val currentUid = authRepository.currentUser.value?.uid

        // BLOCKER FIX: Verify that the restored state belongs to the currently logged-in user.
        // If UIDs mismatch (User B resuming User A's process), discard and force fresh startup.
        if (wasCompleted && destinationId != null && currentUid != null && currentUid == resolvedUid) {
            val restoredDestination = mapIdToRoute(destinationId)
            if (restoredDestination != null) {
                diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_RESTORED", mapOf("destination" to destinationId, "uid" to currentUid))
                _startupState.value = AppStartupState.Ready(restoredDestination)
                
                // Process Restoration implies identity stability
                markAuthReady()

                // If there's a pending event (restored in init), start the observer
                if (pendingStartupEvent != null) {
                    startDeferredNavigationObserver()
                }
                return
            }
        } else if (wasCompleted && resolvedUid != null && currentUid != resolvedUid) {
            diagnosticLogger.warn(DiagnosticCategory.STARTUP, "RESTORATION_UID_MISMATCH", mapOf("saved" to resolvedUid, "current" to (currentUid ?: "null")))
            // Continue to normal startup to re-validate User B
        }
        // ---------------------------------

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
            ID_MNEMONIC_REVEAL -> MnemonicReveal
            else -> null
        }
    }

    private fun mapRouteToId(route: Any): String? {
        return when (route) {
            Home -> ID_HOME
            Login -> ID_LOGIN
            Onboarding -> ID_ONBOARDING
            IdentityReveal -> ID_IDENTITY_REVEAL
            MnemonicReveal -> ID_MNEMONIC_REVEAL
            else -> null
        }
    }

    fun retryStartup() {
        diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_RETRY_TRIGGERED")
        _startupState.value = AppStartupState.Initializing
        startupCoordinator.reset()
        executeStartup()
    }

    private fun executeStartup() {
        viewModelScope.launch {
            try {
                // BLOCKER FIX: Wait for any cross-account cleanup to finish before initializing the new session.
                // This ensures User B never sees User A's cached DataStore/Room identity.
                isCleaning.first { !it }

                startupCoordinator.start()
                
                // BLOCKING: Wait for Core/Security readiness (including App Check)
                startupCoordinator.awaitComponent(StartupComponent.CORE)

                // WAIT FOR Preload Result
                val result = startupCoordinator.preloadResult.first { it != null }
                if (result is PreloadResult.RecoveryRequired) {
                    _startupState.value = AppStartupState.Recovery
                    return@launch
                }

                // BLOCKING: Wait for Database/Encryption readiness (off main thread)
                startupCoordinator.awaitComponent(StartupComponent.DATABASE)
                
                // Only proceed if no terminal error occurred
                if (_startupState.value !is AppStartupState.Error) {
                    determineInitialRoute()
                }
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.STARTUP, "STARTUP_CRITICAL_FAILURE", throwable = e)
                _startupState.value = AppStartupState.Error("An unexpected error occurred during startup.")
                startupCoordinator.completeAll()
            }
        }
    }

    private suspend fun determineInitialRoute() {
        val destination = getInitialDestinationUseCase()
        diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_DESTINATION_RESOLVED", mapOf("destination" to destination.name))

        val baseDestination: Any = when (destination) {
            InitialDestination.ONBOARDING -> Onboarding
            InitialDestination.UNAUTHENTICATED -> Login
            InitialDestination.AUTHENTICATED -> {
                _startupState.value = AppStartupState.Registering
                
                when (val result = registrationCoordinator.ensureProfileExists()) {
                    RegistrationResult.SuccessExistingUser -> Home
                    RegistrationResult.SuccessNewUser -> IdentityReveal
                    is RegistrationResult.Failure -> {
                        diagnosticLogger.error(DiagnosticCategory.STARTUP, "STARTUP_REGISTRATION_FAILED", throwable = result.exception)
                        _startupState.value = AppStartupState.Error("Profile verification failed.")
                        startupCoordinator.completeAll()
                        return
                    }
                }
            }
        }

        // Integration of Deep Link Intent into the first Ready state
        val action = pendingStartupEvent

        // Resolution: If the deep link is a full screen route, it becomes the startDestination
        // if we are authenticated. 
        val finalDestination = if (action is Route && destination == InitialDestination.AUTHENTICATED) {
            pendingStartupEvent = null // Consume Route
            action
        } else {
            baseDestination
        }

        // Only put in startupAction if NOT a Route (Routes are startDestinations)
        // AND we are not letting the deferred observer handle it.
        // Actually, for consistency with tests, we let the deferred observer handle side-effects like IncomingArtifact
        val finalAction = if (finalDestination == action || action is IncomingArtifact) null else action

        updateStartupState(AppStartupState.Ready(finalDestination, finalAction))
        markAuthReady()
    }

    private fun updateStartupState(state: AppStartupState.Ready) {
        _startupState.value = state
        
        // Persist for Process Death Recovery
        val destination = state.startDestination
        val destinationId = mapRouteToId(destination)
        val currentUid = authRepository.currentUser.value?.uid

        if (destinationId != null && currentUid != null) {
            savedStateHandle[KEY_STARTUP_COMPLETED] = true
            savedStateHandle[KEY_RESOLVED_DESTINATION_ID] = destinationId
            savedStateHandle[KEY_RESOLVED_UID] = currentUid
        }
    }

    private fun markAuthReady() {
        startupCoordinator.emitReadiness(StartupComponent.AUTH)
        StartupMetrics.onAuthReady()
    }

    fun onLaunchIntent(intent: android.content.Intent?) {
        val event = parseIntent(intent) ?: return
        
        val currentState = _startupState.value
        if (currentState is AppStartupState.Ready && authRepository.currentUser.value != null) {
            // Warm Start: Application is already ready, deliver via standard channel
            diagnosticLogger.info(DiagnosticCategory.NAV, "WARM_START_INTENT_DELIVERY", mapOf("event" to event.javaClass.simpleName))
            emitNavigationEvent(event)
        } else {
            // Cold Start / Initializing: Buffer for determineInitialRoute()
            diagnosticLogger.info(DiagnosticCategory.NAV, "COLD_START_INTENT_BUFFERED", mapOf("event" to event.javaClass.simpleName))
            pendingStartupEvent = event
            
            // Persist pending event for state restoration
            if (event is IncomingArtifact) {
                try {
                    val json = Json.encodeToString(event)
                    savedStateHandle[KEY_PENDING_EVENT_JSON] = json
                } catch (e: Exception) {
                    diagnosticLogger.warn(DiagnosticCategory.NAV, "PENDING_EVENT_PERSIST_FAILED")
                }
            }
            
            // If already Ready (but maybe not authenticated or just entered Ready), the observer will pick it up
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
                diagnosticLogger.info(DiagnosticCategory.NAV, "DEEP_LINK_DELIVERED", mapOf("event" to event.javaClass.simpleName))
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

        // 2. Resolve Navigation Target from Notification Extras (Higher precedence)
        val type = intent.getStringExtra("notificationType")
        val artifactId = intent.getStringExtra("artifactId")
        val userId = intent.getStringExtra("userId")

        if (type == "FOLLOW" && !userId.isNullOrBlank()) {
            return Profile(userId)
        }

        if (!artifactId.isNullOrBlank()) {
            return IncomingArtifact(artifactId)
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
        diagnosticLogger.debug(DiagnosticCategory.NAV, "SCREEN_ENTERED", mapOf("route" to (route ?: "null")))
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
