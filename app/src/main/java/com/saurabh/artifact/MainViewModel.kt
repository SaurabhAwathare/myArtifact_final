package com.saurabh.artifact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.navigation.Screen
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.util.OnboardingManager
import com.saurabh.artifact.startup.StartupCoordinator
import com.saurabh.artifact.startup.StartupMetrics
import com.saurabh.artifact.startup.StartupStage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AppStartupState {
    object Initializing : AppStartupState()
    data class Ready(val startDestination: String) : AppStartupState()
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val onboardingManager: OnboardingManager,
    private val authRepository: AuthRepository,
    private val startupCoordinator: StartupCoordinator
) : ViewModel() {

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.Initializing)
    val startupState = _startupState.asStateFlow()

    val startupStage = startupCoordinator.stage

    val isStable = startupCoordinator.stage
        .map { it == StartupStage.STABLE }
        .distinctUntilChanged()
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
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Startup error", e)
                _startupState.value = AppStartupState.Ready(Screen.Login.route)
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
}
