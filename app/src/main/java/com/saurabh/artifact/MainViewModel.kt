package com.saurabh.artifact

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.navigation.*
import com.saurabh.artifact.model.PlaybackSource
import com.saurabh.artifact.domain.ArtifactVisibilityFilter
import com.saurabh.artifact.domain.auth.GetInitialDestinationUseCase
import com.saurabh.artifact.domain.auth.InitialDestination
import com.saurabh.artifact.domain.auth.RegistrationResult
import com.saurabh.artifact.domain.settings.ObserveStealthModeUseCase
import com.saurabh.artifact.startup.StartupComponent
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.SecurityStatus
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.security.PreloadResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val securityStatus: SecurityStatus = SecurityStatus.PENDING
    ) : AppStartupState()
    data class Error(val message: String) : AppStartupState()
}

@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: com.saurabh.artifact.repository.AuthRepository,
    private val getInitialDestinationUseCase: GetInitialDestinationUseCase,
    private val registrationCoordinator: com.saurabh.artifact.domain.auth.RegistrationCoordinator,
    private val logoutCoordinator: com.saurabh.artifact.domain.auth.LogoutCoordinator,
    private val maintenanceRepository: com.saurabh.artifact.repository.MaintenanceRepository,
    private val sessionManager: com.saurabh.artifact.data.local.UserSessionManager,
    private val userProfileManager: com.saurabh.artifact.repository.UserProfileManager,
    private val visibilityFilter: ArtifactVisibilityFilter,
    observeStealthModeUseCase: ObserveStealthModeUseCase,
    private val startupCoordinator: StartupCoordinator,
    private val savedStateHandle: SavedStateHandle,
    private val diagnosticLogger: DiagnosticLogger,
) : ViewModel() {

    companion object {
        private const val KEY_STARTUP_COMPLETED = "startup_completed"
        private const val KEY_RESOLVED_DESTINATION_ID = "resolved_destination_id"
        private const val KEY_RESOLVED_UID = "resolved_uid"
        private const val KEY_PENDING_EVENTS_QUEUE = "pending_events_queue"
        
        private const val MAX_PENDING_EVENTS = 5

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
    val securityStatus = startupCoordinator.securityStatus

    val isStealthModeEnabled = observeStealthModeUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val ignoredUsers = authRepository.currentUser
        .flatMapLatest { user ->
            if (user != null) {
                visibilityFilter.observeIgnoredUserIds(user.uid)
            } else {
                flowOf(emptySet())
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _isCurrentScreenSensitive = MutableStateFlow(false)

    val isSecureFlagRequired = combine(
        isStealthModeEnabled,
        _isCurrentScreenSensitive
    ) { stealth, sensitive -> stealth || sensitive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _navigationEvent = Channel<Any>(capacity = Channel.BUFFERED)
    val navigationEvent = _navigationEvent.receiveAsFlow()

    private val pendingStartupEvents = mutableListOf<Any>()
    private var deferredNavigationJob: Job? = null

    private val started = AtomicBoolean(false)

    init {
        // Restore pending events from SavedState
        restorePendingEventsFromSavedState()

        // Reactive Safety Sync Lifecycle Management
        // Ensures synchronization is established for every authenticated session 
        // regardless of startup path, and respects the cleanup barrier.
        combine(
            authRepository.currentUser,
            isCleaning
        ) { user, cleaning ->
            user?.uid to cleaning
        }
        .distinctUntilChanged()
        .onEach { (uid, cleaning) ->
            if ((uid != null) && !cleaning) {
                userProfileManager.initializeSafetySync(uid)
            } else if (uid == null) {
                userProfileManager.stopSafetySync()
            }
        }
        .launchIn(viewModelScope)

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
                    
                    val isUidChange = (previousUid != currentUid)
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
                                val result = logoutCoordinator.performFullCleanup()
                                if (result.isFullySuccessful) {
                                    diagnosticLogger.info(DiagnosticCategory.AUTH, "ACCOUNT_CLEANUP_COMPLETED")
                                } else {
                                    diagnosticLogger.error(DiagnosticCategory.AUTH, "ACCOUNT_CLEANUP_INCOMPLETE", mapOf("result" to result.toString()))
                                }
                            } catch (_: Exception) {
                                diagnosticLogger.error(DiagnosticCategory.AUTH, "ACCOUNT_CLEANUP_FAILED")
                            } finally {
                                _isCleaning.value = false
                                if (currentUid == null) {
                                    // Hard Reset to Login screen only if we are now logged out
                                    _startupState.value = AppStartupState.Ready(
                                        startDestination = Login,
                                        securityStatus = startupCoordinator.securityStatus.value
                                    )
                                }
                            }
                        }
                    }
                }
        }

        // Single observation of terminal startup errors
        startupCoordinator.terminalError
            .filterNotNull()
            .onEach { error ->
                diagnosticLogger.error(DiagnosticCategory.STARTUP, "STARTUP_TERMINAL_FAILURE", throwable = error)
                val message = error.message ?: "Security initialization failed."
                _startupState.value = AppStartupState.Error(message)
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
                viewModelScope.launch {
                    // Safe Restoration: Await technical readiness before exposing UI
                    // Guard 1: Core Security (App Check)
                    startupCoordinator.awaitComponent(StartupComponent.CORE)

                    // Guard 2: Preload Result Inspection (Detect recovery before database lock)
                    val result = startupCoordinator.preloadResult.first { it != null }
                    if (result is PreloadResult.RecoveryRequired) {
                        diagnosticLogger.warn(DiagnosticCategory.STARTUP, "RESTORATION_RECOVERY_TRIGGERED")
                        _startupState.value = AppStartupState.Recovery
                        return@launch
                    }

                    // Guard 3: Database Readiness
                    startupCoordinator.awaitComponent(StartupComponent.DATABASE)

                    diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_RESTORED", mapOf("destination" to destinationId, "uid" to currentUid))
                    _startupState.value = AppStartupState.Ready(
                        startDestination = restoredDestination,
                        securityStatus = startupCoordinator.securityStatus.value
                    )
                    
                    // Process Restoration implies identity stability
                    markAuthReady()

                    // If there's a pending event (restored in init), start the observer
                    if (pendingStartupEvents.isNotEmpty()) {
                        startDeferredNavigationObserver()
                    }
                }
                return
            }
        } else if (wasCompleted && resolvedUid != null && currentUid != resolvedUid) {
            diagnosticLogger.warn(DiagnosticCategory.STARTUP, "RESTORATION_UID_MISMATCH", mapOf("saved" to resolvedUid, "current" to (currentUid ?: "null")))
            // Continue to normal startup to re-validate User B
        }
        // ---------------------------------

        diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_BEGIN")

        executeStartup()
    }

    /**
     * Proactively verifies that the local data boundary is intact during startup.
     * Triggers a comprehensive cleanup if a pending deletion or account mismatch is detected.
     * Returns true if the boundary is clean (either no cleanup needed or successful cleanup),
     * or false if a critical failure occurred that must block startup.
     */
    private suspend fun checkLocalAccountBoundary(): Boolean {
        val currentUid = authRepository.currentUserId
        val owningUid = sessionManager.owningUid.first()
        val pendingDeletionUid = maintenanceRepository.getPendingDeletionUid()
        val isLoggingOut = sessionManager.isLoggingOut.first()
        
        // Scenario 1: Interrupted deletion detected (Maintenance Lock)
        val isDeletionInterrupted = pendingDeletionUid != null
        
        // Scenario 2: owningUid mismatch (dirty state from a previous user detected)
        val isUidMismatch = currentUid.isNotEmpty() && owningUid != null && owningUid != currentUid
        
        // Scenario 3: App started logged-out but local account state exists (Dirty Logged Out)
        val isDirtyLoggedOut = currentUid.isEmpty() && owningUid != null

        if (isDeletionInterrupted || isUidMismatch || isDirtyLoggedOut || isLoggingOut) {
            val reason = when {
                isDeletionInterrupted -> "pending_deletion"
                isLoggingOut -> "interrupted_logout"
                isUidMismatch -> "uid_mismatch"
                else -> "dirty_logged_out"
            }
            
            diagnosticLogger.info(
                DiagnosticCategory.AUTH, 
                "STARTUP_BOUNDARY_CLEANUP_TRIGGERED", 
                mapOf("reason" to reason)
            )
            
            return try {
                _isCleaning.value = true
                val result = logoutCoordinator.performFullCleanup()
                
                if (result.isFullySuccessful) {
                    // Clear maintenance lock only if it was the reason for cleanup to prevent infinite loops
                    if (isDeletionInterrupted) {
                        maintenanceRepository.setPendingDeletion(null)
                    }
                    true
                } else {
                    diagnosticLogger.error(DiagnosticCategory.AUTH, "STARTUP_CLEANUP_INCOMPLETE", mapOf("result" to result.toString()))
                    _startupState.value = AppStartupState.Error("Data maintenance required. Please restart the app.")
                    false
                }
            } catch (e: Exception) {
                diagnosticLogger.error(DiagnosticCategory.AUTH, "STARTUP_CLEANUP_FAILED", throwable = e)
                _startupState.value = AppStartupState.Error("Security boundary violation. Please contact support.")
                false
            } finally {
                _isCleaning.value = false
            }
        }
        return true
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
                // BLOCKER FIX: Proactively detect and clear stale local state or interrupted deletions
                // before any system initialization occurs. This ensures no data leakage to new accounts.
                if (!checkLocalAccountBoundary()) {
                    return@launch // Abort on critical cleanup failure
                }

                // BLOCKER FIX: Wait for any cross-account cleanup to finish before initializing the new session.
                // This ensures User B never sees User A's cached DataStore/Room identity.
                isCleaning.first { !it }

                // --- RESCUE MODE HARDENING ---
                // We check for Rescue Mode AFTER the account boundary is verified.
                // This ensures that even in Rescue Mode, we don't expose stale user data.
                if (startupCoordinator.isRescueModeActive) {
                    diagnosticLogger.warn(DiagnosticCategory.STARTUP, "STARTUP_RESCUE_MODE_DETECTED")
                    _startupState.value = AppStartupState.Rescue
                    return@launch
                }

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
        // TERMINAL GUARD: If a timeout or other terminal failure occurred while we were waiting
        // for database readiness, abort determination to prevent UI flickering or invalid states.
        if (startupCoordinator.terminalError.value != null) {
            diagnosticLogger.warn(DiagnosticCategory.STARTUP, "STARTUP_DETERMINATION_ABORTED_ERROR")
            return
        }

        val destination = getInitialDestinationUseCase()
        diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_DESTINATION_RESOLVED", mapOf("destination" to destination.name))

        val baseDestination: Any = when (destination) {
            InitialDestination.ONBOARDING -> Onboarding
            InitialDestination.UNAUTHENTICATED -> Login
            InitialDestination.AUTHENTICATED -> {
                _startupState.value = AppStartupState.Registering
                
                when (val result = registrationCoordinator.ensureProfileExists()) {
                    RegistrationResult.SuccessExistingUser -> {
                        Home
                    }
                    RegistrationResult.SuccessNewUser -> {
                        IdentityReveal
                    }
                    is RegistrationResult.Failure -> {
                        diagnosticLogger.error(DiagnosticCategory.STARTUP, "STARTUP_REGISTRATION_FAILED", throwable = result.exception)
                        val message = if (result.exception.message?.contains("terminated") == true) {
                            result.exception.message!!
                        } else {
                            "Profile verification failed."
                        }
                        _startupState.value = AppStartupState.Error(message)
                        startupCoordinator.completeAll()
                        return
                    }
                }
            }
        }

        // Integration of Deep Link Intent into the first Ready state
        // RESOLUTION: If the queue contains a Route, the LAST one becomes the startDestination
        // and is removed from the queue.
        val lastRouteIndex = pendingStartupEvents.indexOfLast { it is Route }
        val finalDestination = if (lastRouteIndex != -1 && destination == InitialDestination.AUTHENTICATED) {
            pendingStartupEvents.removeAt(lastRouteIndex) as Route
        } else {
            baseDestination
        }

        updateStartupState(AppStartupState.Ready(
            startDestination = finalDestination, 
            startupAction = null, // All remaining events are handled by the deferred observer
            securityStatus = startupCoordinator.securityStatus.value
        ))
        markAuthReady()
    }

    private fun updateStartupState(state: AppStartupState.Ready) {
        // TERMINAL GUARD: Ensure we don't transition to Ready if a terminal error was just emitted.
        if (startupCoordinator.terminalError.value != null) return

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
        viewModelScope.launch {
            val uid = authRepository.currentUserId
            if (!uid.isNullOrBlank()) {
                // Phase 6.10.1: Reactive Sync for Cross-Device Consistency
                launch { visibilityFilter.syncIgnoredUsersFromRemote(uid, this).collect() }
                launch { visibilityFilter.syncReportsFromRemote(uid, this).collect() }
            }
        }
    }

    fun onLaunchIntent(intent: android.content.Intent?) {
        val event = parseIntent(intent) ?: return
        
        // IGNORE GUARD: Block navigation from ignored actors
        val actorId = when (event) {
            is Profile -> event.userId
            is IncomingArtifact -> event.actorId
            else -> null
        }
        
        if (actorId != null && ignoredUsers.value.contains(actorId)) {
            diagnosticLogger.warn(DiagnosticCategory.AUTH, "NAVIGATION_REJECTED_IGNORED_ACTOR", mapOf("actorId" to actorId))
            return
        }

        val currentState = _startupState.value
        if (currentState is AppStartupState.Ready && authRepository.currentUser.value != null) {
            // Warm Start: Application is already ready, deliver via standard channel
            diagnosticLogger.info(DiagnosticCategory.NAV, "WARM_START_INTENT_DELIVERY", mapOf("event" to event.javaClass.simpleName))
            emitNavigationEvent(event)
        } else {
            // Cold Start / Initializing: Buffer for determineInitialRoute()
            diagnosticLogger.info(DiagnosticCategory.NAV, "COLD_START_INTENT_BUFFERED", mapOf("event" to event.javaClass.simpleName))
            
            // BOUNDED FIFO QUEUE: Ensure no data loss while maintaining memory safety
            if (pendingStartupEvents.size >= MAX_PENDING_EVENTS) {
                pendingStartupEvents.removeAt(0)
                diagnosticLogger.warn(DiagnosticCategory.NAV, "PENDING_EVENTS_QUEUE_OVERFLOW")
            }
            pendingStartupEvents.add(event)
            
            // Persist queue for state restoration
            savePendingEventsToSavedState()
            
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

            // 2. Deliver all queued events exactly once
            val eventsToDeliver = ArrayList(pendingStartupEvents)
            pendingStartupEvents.clear()
            savedStateHandle.remove<List<String>>(KEY_PENDING_EVENTS_QUEUE)

            eventsToDeliver.forEach { event ->
                // IGNORE GUARD: Re-verify against latest ignored list
                val actorId = when (event) {
                    is Profile -> event.userId
                    is IncomingArtifact -> event.actorId
                    else -> null
                }

                if (actorId != null && ignoredUsers.value.contains(actorId)) {
                    diagnosticLogger.warn(DiagnosticCategory.AUTH, "STARTUP_NAVIGATION_REJECTED_IGNORED_ACTOR", mapOf("actorId" to actorId))
                    return@forEach
                }

                if (event is IncomingArtifact && event.source == PlaybackSource.NOTIFICATION) {
                    val currentUid = authRepository.currentUserId
                    // RECIPIENT HARDENING: Fail closed if recipientId is missing or mismatched
                    // Backend contract (functions/src/index.ts) guarantees recipientId for new notifications.
                    if (event.recipientId == null || event.recipientId != currentUid) {
                        diagnosticLogger.error(
                            DiagnosticCategory.AUTH, 
                            "NOTIFICATION_REJECTED_INVALID_RECIPIENT", 
                            mapOf("recipientId" to (event.recipientId ?: "null"), "currentUserId" to currentUid)
                        )
                        return@forEach
                    }
                }

                diagnosticLogger.info(DiagnosticCategory.NAV, "DEEP_LINK_DELIVERED", mapOf("event" to event.javaClass.simpleName))
                emitNavigationEvent(event)
            }
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
        val recipientId = intent.getStringExtra("recipientId")

        if (type == "FOLLOW" && !userId.isNullOrBlank()) {
            return Profile(personaId = userId)
        }

        if (!artifactId.isNullOrBlank()) {
            return IncomingArtifact(
                artifactId = artifactId, 
                source = PlaybackSource.NOTIFICATION,
                recipientId = recipientId,
                actorId = userId // userId in FCM payload is the actorId
            )
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
                        return IncomingArtifact(artifactId, PlaybackSource.DEEP_LINK)
                    }
                }
            }
        }

        return null
    }

    private fun emitNavigationEvent(event: Any) {
        if (event is IncomingArtifact && event.source == PlaybackSource.NOTIFICATION) {
            val currentUid = authRepository.currentUserId
            // RECIPIENT HARDENING: Fail closed if recipientId is missing or mismatched (Warm Start)
            if (event.recipientId == null || event.recipientId != currentUid) {
                diagnosticLogger.error(
                    DiagnosticCategory.AUTH, 
                    "NOTIFICATION_REJECTED_INVALID_RECIPIENT_WARM", 
                    mapOf("recipientId" to (event.recipientId ?: "null"), "currentUserId" to currentUid)
                )
                return
            }
        }
        
        viewModelScope.launch {
            _navigationEvent.send(event)
        }
    }

    private fun savePendingEventsToSavedState() {
        val strings = pendingStartupEvents.mapNotNull { event ->
            try {
                when (event) {
                    is IncomingArtifact -> "PLAY:" + Json.encodeToString(event)
                    is Route -> "NAV:" + Json.encodeToString<Route>(event)
                    else -> null
                }
            } catch (_: Exception) { null }
        }
        savedStateHandle[KEY_PENDING_EVENTS_QUEUE] = ArrayList(strings)
    }

    private fun restorePendingEventsFromSavedState() {
        savedStateHandle.get<List<String>>(KEY_PENDING_EVENTS_QUEUE)?.forEach { encoded ->
            try {
                val event = when {
                    encoded.startsWith("PLAY:") -> Json.decodeFromString<IncomingArtifact>(encoded.substring(5))
                    encoded.startsWith("NAV:") -> Json.decodeFromString<Route>(encoded.substring(4))
                    else -> null
                }
                event?.let { pendingStartupEvents.add(it) }
            } catch (_: Exception) {}
        }
        if (pendingStartupEvents.isNotEmpty()) {
            diagnosticLogger.info(DiagnosticCategory.STARTUP, "STARTUP_EVENTS_RESTORED", mapOf("count" to pendingStartupEvents.size))
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
