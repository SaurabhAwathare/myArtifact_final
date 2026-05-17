package com.saurabh.artifact.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.AudioPlayer
import com.saurabh.artifact.model.Artifact
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val audioPlayer: AudioPlayer,
    private val authRepository: AuthRepository,
    private val recordingRepository: com.saurabh.artifact.repository.RecordingRepository,
    private val listeningProgressTracker: com.saurabh.artifact.audio.ListeningProgressTracker,
    private val commentUnlockRepository: com.saurabh.artifact.repository.CommentUnlockRepository
) : ViewModel() {

    private val _isExpanded = MutableStateFlow(false)
    private val _showAdvancedControls = MutableStateFlow(false)
    
    // Tracks if the user has listened enough to unlock commenting (e.g., 80%)
    private val _isCommentUnlocked = MutableStateFlow(false)

    private val _sleepTimerMillisRemaining = MutableStateFlow<Long?>(null)
    private var sleepTimerJob: Job? = null

    init {
        // FIX 11: Reset player state on logout
        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                if (user == null) {
                    resetState()
                }
            }
        }

        // Update unlock state if threshold met
        viewModelScope.launch {
            listeningProgressTracker.sessionState.collect { session ->
                if (session?.isThresholdMet == true) {
                    android.util.Log.d("ReviewDebug", "Threshold met for ${session.artifactId}")
                    if (!_isCommentUnlocked.value) {
                        android.util.Log.d("ReviewDebug", "Unlocking artifact: ${session.artifactId}")
                        _isCommentUnlocked.value = true
                        commentUnlockRepository.unlockArtifact(session.artifactId)
                    }
                }
            }
        }

        // Sync local unlock state when artifact changes
        viewModelScope.launch {
            audioPlayer.currentArtifact.collect { artifact ->
                if (artifact != null) {
                    commentUnlockRepository.isUnlocked(artifact.id).collect { unlocked ->
                        if (unlocked) _isCommentUnlocked.value = true
                    }
                } else {
                    _isCommentUnlocked.value = false
                }
            }
        }
    }

    private fun resetState() {
        _isExpanded.value = false
        _showAdvancedControls.value = false
        _isCommentUnlocked.value = false
        _sleepTimerMillisRemaining.value = null
        sleepTimerJob?.cancel()
        audioPlayer.stop()
    }
    
    val uiState: StateFlow<PlayerUiState> = combine(
        audioPlayer.currentArtifact,
        audioPlayer.isPlaying,
        audioPlayer.isBuffering,
        audioPlayer.currentPosition,
        audioPlayer.duration,
        audioPlayer.playbackSpeed,
        audioPlayer.isSkipSilenceEnabled,
        _isCommentUnlocked,
        _isExpanded,
        _showAdvancedControls,
        _sleepTimerMillisRemaining,
        listeningProgressTracker.sessionState
    ) { params: Array<Any?> ->
        val artifact = params[0] as Artifact?
        val isPlaying = params[1] as Boolean
        val isBuffering = params[2] as Boolean
        val position = params[3] as Long
        val duration = params[4] as Long
        val speed = params[5] as Float
        val isSkipSilenceEnabled = params[6] as Boolean
        val commentUnlocked = params[7] as Boolean
        val expanded = params[8] as Boolean
        val showAdvanced = params[9] as Boolean
        val sleepTimer = params[10] as Long?
        val sessionState = params[11] as com.saurabh.artifact.model.PlaybackSessionState?

        val progress = if (duration > 0) position.toFloat() / duration else 0f
        val furthestProgress = sessionState?.progress ?: 0f

        val currentSegment = artifact?.transcript?.find { position in it.startMs..it.endMs }

        val mode = when {
            artifact == null -> PlayerMode.HIDDEN
            expanded -> PlayerMode.FULLSCREEN
            else -> PlayerMode.MINI
        }

        PlayerUiState(
            currentArtifact = artifact,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentPosition = position,
            duration = duration,
            playbackSpeed = speed,
            isCommentUnlocked = commentUnlocked,
            listeningProgress = furthestProgress, // Use furthest progress for the "unlocking" feel
            isExpanded = expanded,
            playerMode = mode,
            isSilenceSkipEnabled = isSkipSilenceEnabled,
            sleepTimerMillisRemaining = sleepTimer,
            currentTranscriptSegment = currentSegment,
            showAdvancedControls = showAdvanced
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlayerUiState()
    )

    fun playArtifact(artifact: Artifact) {
        _isCommentUnlocked.value = false // Reset for new artifact
        audioPlayer.play(artifact)
    }

    fun togglePlayPause() {
        audioPlayer.togglePlayPause()
    }

    fun seekTo(position: Long) {
        audioPlayer.seekTo(position)
    }

    fun setPlaybackSpeed(speed: Float) {
        audioPlayer.setPlaybackSpeed(speed)
    }

    fun toggleSilenceSkipping() {
        audioPlayer.setSkipSilenceEnabled(!audioPlayer.isSkipSilenceEnabled.value)
    }

    fun setExpanded(expanded: Boolean) {
        _isExpanded.value = expanded
    }

    fun setShowAdvancedControls(show: Boolean) {
        _showAdvancedControls.value = show
    }

    fun rewind() {
        val newPos = (audioPlayer.currentPosition.value - 15000).coerceAtLeast(0)
        audioPlayer.seekTo(newPos)
    }

    fun forward() {
        val newPos = (audioPlayer.currentPosition.value + 15000).coerceAtMost(audioPlayer.duration.value)
        audioPlayer.seekTo(newPos)
    }

    fun deleteCurrentArtifact() {
        val artifact = uiState.value.currentArtifact ?: return
        if (artifact.isDraft) {
            viewModelScope.launch {
                // Find the draft entity to delete
                // Assuming artifact.id matches draft entity id
                val draft = recordingRepository.getDraft(artifact.id)
                if (draft != null) {
                    recordingRepository.deleteDraft(draft)
                }
                audioPlayer.stop()
                _isExpanded.value = false
            }
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes == 0) {
            _sleepTimerMillisRemaining.value = null
            return
        }

        val totalMillis = minutes * 60 * 1000L
        _sleepTimerMillisRemaining.value = totalMillis
        
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalMillis
            while (remaining > 0) {
                delay(1000)
                remaining -= 1000
                _sleepTimerMillisRemaining.value = remaining
                
                if (!audioPlayer.isPlaying.value) continue // Only countdown when playing? Or always?
                // Traditional sleep timers usually countdown always.
            }
            // Fade out and stop
            fadeOutAndStop()
            _sleepTimerMillisRemaining.value = null
        }
    }

    private suspend fun fadeOutAndStop() {
        // Simple fade out if supported, otherwise just stop
        audioPlayer.stop()
    }
}
