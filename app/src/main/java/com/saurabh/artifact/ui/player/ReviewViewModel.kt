package com.saurabh.artifact.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.ReviewSessionManager
import com.saurabh.artifact.data.local.DraftDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReviewViewModel @Inject constructor(
    reviewSessionManager: ReviewSessionManager,
    private val playbackCoordinator: PlaybackCoordinator,
    private val draftDao: DraftDao
) : ViewModel() {

    val reviewState = reviewSessionManager.reviewProgress
    val playbackState = combine(
        playbackCoordinator.isPlaying,
        playbackCoordinator.currentPosition,
        playbackCoordinator.playbackSpeed
    ) { isPlaying, position, speed ->
        PlaybackUiState(isPlaying, position.inWholeMilliseconds, speed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackUiState())

    val draft = reviewState.flatMapLatest { state ->
        if (state.artifactId != null) {
            draftDao.observeDraftById(state.artifactId)
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val transcriptSegments = draft.map { draftEntity ->
        draftEntity?.transcriptSegmentsJson?.toUnsecureString()?.let { json ->
            try {
                kotlinx.serialization.json.Json.decodeFromString<List<com.saurabh.artifact.model.TranscriptSegment>>(json)
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startReview(draftId: String) {
        playbackCoordinator.playDraftPreview(draftId)
    }

    fun togglePlayback() {
        playbackCoordinator.togglePlayPause()
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackCoordinator.setPlaybackSpeed(speed)
    }

    fun seekToPosition(positionMs: Long) {
        playbackCoordinator.seekTo(positionMs.milliseconds)
    }

    fun rewind() {
        viewModelScope.launch {
            val current = playbackCoordinator.currentPosition.first()
            playbackCoordinator.seekTo((current - 10.seconds).coerceAtLeast(Duration.ZERO))
        }
    }

    fun forward() {
        viewModelScope.launch {
            val current = playbackCoordinator.currentPosition.first()
            val duration = reviewState.value.durationMs.milliseconds
            playbackCoordinator.seekTo((current + 10.seconds).coerceAtMost(duration))
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop draft preview playback when the review screen is dismissed
        playbackCoordinator.requestStop(com.saurabh.artifact.audio.PlaybackType.DRAFT_PREVIEW)
    }
}

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f
)
