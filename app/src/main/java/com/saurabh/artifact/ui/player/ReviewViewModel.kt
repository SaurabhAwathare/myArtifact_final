package com.saurabh.artifact.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.ReviewSessionManager
import com.saurabh.artifact.data.local.DraftDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val reviewSessionManager: ReviewSessionManager,
    private val playbackCoordinator: PlaybackCoordinator,
    private val draftDao: DraftDao
) : ViewModel() {

    val reviewState = reviewSessionManager.reviewProgress
    val playbackState = combine(
        playbackCoordinator.isPlaying,
        playbackCoordinator.currentPosition,
        playbackCoordinator.playbackSpeed
    ) { isPlaying, position, speed ->
        PlaybackUiState(isPlaying, position, speed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackUiState())

    val draft = reviewState.flatMapLatest { state ->
        if (state.artifactId != null) {
            draftDao.observeDraftById(state.artifactId)
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun startReview(draftId: String) {
        playbackCoordinator.playDraftPreview(draftId)
    }

    fun togglePlayback() {
        playbackCoordinator.togglePlayPause()
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackCoordinator.setPlaybackSpeed(speed)
    }

    fun seekTo(progress: Float) {
        val duration = reviewState.value.durationMs
        playbackCoordinator.seekTo((progress * duration).toLong())
    }

    fun rewind() {
        val current = playbackCoordinator.currentPosition.value
        playbackCoordinator.seekTo((current - 10000).coerceAtLeast(0))
    }

    fun forward() {
        val current = playbackCoordinator.currentPosition.value
        val duration = reviewState.value.durationMs
        playbackCoordinator.seekTo((current + 10000).coerceAtMost(duration))
    }

    override fun onCleared() {
        super.onCleared()
        // Playback ownership is now handled by the Coordinator.
    }
}

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f
)
