package com.saurabh.artifact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.data.local.DraftDao
import com.saurabh.artifact.navigation.Screen
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.SettingsRepository
import com.saurabh.artifact.util.OnboardingManager
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.startup.StartupStage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

sealed class AppStartupState {
    object Initializing : AppStartupState()
    data class Ready(val startDestination: String) : AppStartupState()
}

data class BackupWarning(
    val draftId: String,
    val title: String?,
    val reason: String
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val onboardingManager: OnboardingManager,
    private val authRepository: AuthRepository,
    settingsRepository: SettingsRepository,
    startupCoordinator: StartupCoordinator,
    private val draftDao: DraftDao
) : ViewModel() {

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.Initializing)
    val startupState = _startupState.asStateFlow()

    private val _backupWarning = MutableStateFlow<BackupWarning?>(null)
    val backupWarning = _backupWarning.asStateFlow()

    private val _reportingArtifactId = MutableStateFlow<String?>(null)
    val reportingArtifactId = _reportingArtifactId.asStateFlow()

    val startupStage = startupCoordinator.stage

    val currentUserProfile = authRepository.userData

    val isStable = startupCoordinator.stage
        .map { it == StartupStage.STABLE }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isStealthModeEnabled = settingsRepository.userSettings
        .map { it.stealthModeEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _navigationEvent = MutableSharedFlow<String>(replay = 0)
    val navigationEvent = _navigationEvent.asSharedFlow()

    private var isStarted = false

    /**
     * Executes the session-aware startup sequence.
     * Determines the initial route BEFORE the UI is allowed to transition from the Splash Screen.
     */
    fun start() {
        if (isStarted) return
        isStarted = true

        viewModelScope.launch {
            try {
                determineInitialRoute()
                checkBackups()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Startup error", e)
                _startupState.value = AppStartupState.Ready(Screen.Login.route)
            }
        }
    }

    /**
     * Scans drafts to identify valuable data that is at risk of loss.
     */
    private suspend fun checkBackups() {
        // In a real app, check a setting like 'isBackupEnabled' first.
        val drafts = draftDao.getAllDrafts()
        val now = System.currentTimeMillis()
        
        for (draft in drafts) {
            // Criteria for "Valuable" and "Unprotected"
            val isValuable = draft.durationMs > 10.minutes.inWholeMilliseconds || 
                             (now - draft.updatedAt) > 3.days.inWholeMilliseconds ||
                             File(draft.localAudioPath).length() > 20 * 1024 * 1024
            
            // Assume if it has no remoteArtifactId, it's not backed up
            val isUnprotected = draft.remoteArtifactId == null
            
            if (isValuable && isUnprotected) {
                _backupWarning.value = BackupWarning(
                    draftId = draft.id,
                    title = draft.title,
                    reason = "This valuable draft exists only on this device."
                )
                break // Show only one warning at a time
            }
        }
    }

    private suspend fun determineInitialRoute() {
        val firebaseUser = authRepository.currentUser.value
        val onboardingCompleted = onboardingManager.isOnboardingCompleted.first()

        val destination = when {
            !onboardingCompleted -> Screen.Onboarding.route
            firebaseUser != null -> Screen.Home.route
            else -> Screen.Login.route
        }

        _startupState.value = AppStartupState.Ready(destination)
        StartupMetrics.onAuthReady()
        android.util.Log.d("AppStartup", "Startup sequence complete. Destination: $destination")
    }

    fun onNewIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("navigate_to_recording", false) == true) {
            viewModelScope.launch {
                _navigationEvent.emit(Screen.InstantRecord.route)
            }
        }
    }

    fun showReportSheet(artifactId: String) {
        _reportingArtifactId.value = artifactId
    }

    fun dismissReportSheet() {
        _reportingArtifactId.value = null
    }
}
