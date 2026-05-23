package com.saurabh.artifact.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackSessionManager
import com.saurabh.artifact.audio.ReviewSessionManager
import com.saurabh.artifact.data.local.DraftDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val reviewSessionManager: ReviewSessionManager,
    private val playbackSessionManager: PlaybackSessionManager,
    private val draftDao: DraftDao
) : ViewModel() {

    val reviewState = reviewSessionManager.reviewProgress
    val playbackState = combine(
        playbackSessionManager.isPlaying,
        playbackSessionManager.currentPosition,
        playbackSessionManager.playbackSpeed
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
        reviewSessionManager.startReview(draftId)
    }

    fun togglePlayback() {
        playbackSessionManager.togglePlayPause()
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSessionManager.setPlaybackSpeed(speed)
    }

    fun seekTo(progress: Float) {
        val duration = reviewState.value.durationMs
        playbackSessionManager.seekTo((progress * duration).toLong())
    }

    override fun onCleared() {
        super.onCleared()
        reviewSessionManager.stopReview()
    }
}

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f
)
