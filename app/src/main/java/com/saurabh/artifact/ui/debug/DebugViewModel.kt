package com.saurabh.artifact.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.BuildConfig
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.DebugRepository
import com.saurabh.artifact.repository.DebugSettings
import com.saurabh.artifact.security.UploadGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugUiState(
    val userId: String = "Unknown",
    val deviceId: String = "Unknown",
    val appVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    val settings: DebugSettings = DebugSettings()
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val debugRepository: DebugRepository,
    private val authRepository: AuthRepository,
    private val uploadGuard: UploadGuard
) : ViewModel() {

    val uiState: StateFlow<DebugUiState> = combine(
        authRepository.currentUser.map { it?.uid ?: "Not Authenticated" },
        debugRepository.debugSettings
    ) { uid, settings ->
        DebugUiState(
            userId = uid,
            deviceId = uploadGuard.getDeviceFingerprint(),
            settings = settings
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DebugUiState()
    )

    fun toggleMockTopics(enabled: Boolean) {
        viewModelScope.launch {
            debugRepository.updateUseMockTopics(enabled)
        }
    }

    fun toggleDebugOverlays(enabled: Boolean) {
        viewModelScope.launch {
            debugRepository.updateShowDebugOverlays(enabled)
        }
    }
}

// Helper to use combine with StateFlow in a concise way if not available
private fun <T1, T2, R> combine(
    flow1: kotlinx.coroutines.flow.Flow<T1>,
    flow2: kotlinx.coroutines.flow.Flow<T2>,
    transform: suspend (T1, T2) -> R
): kotlinx.coroutines.flow.Flow<R> = kotlinx.coroutines.flow.combine(flow1, flow2, transform)
