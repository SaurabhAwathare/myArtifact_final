package com.saurabh.artifact.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.AudioPlayer
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.FeedRepository
import com.saurabh.artifact.service.FeedComposer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ForYouFeedViewModel @Inject constructor(
    private val feedComposer: FeedComposer,
    private val feedRepository: FeedRepository,
    private val artifactRepository: com.saurabh.artifact.repository.ArtifactRepository,
    private val authRepository: AuthRepository,
    val audioPlayer: AudioPlayer,
    private val reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager
) : ViewModel() {

    private val _feedState = MutableStateFlow<FeedCompositionState>(FeedCompositionState.Loading)
    val feedState: StateFlow<FeedCompositionState> = _feedState.asStateFlow()

    private val _currentlyPlayingArtifact = MutableStateFlow<Artifact?>(null)
    val currentlyPlayingArtifact: StateFlow<Artifact?> = _currentlyPlayingArtifact

    val isPlaying = audioPlayer.isPlaying
    val currentPosition = audioPlayer.currentPosition

    init {
        loadFeed()
        observePlayback()
    }

    fun loadFeed() {
        val userId = authRepository.currentUser.value?.uid ?: return
        viewModelScope.launch {
            _feedState.value = FeedCompositionState.Loading
            try {
                val feed = feedComposer.composeFeed(userId)
                _feedState.value = FeedCompositionState.Success(feed)
            } catch (ignore: Exception) {
                _feedState.value = FeedCompositionState.Error("A quiet moment was interrupted. Please try again.")
            }
        }
    }

    fun playArtifact(feedArtifact: FeedArtifact) {
        val artifact = feedArtifact.artifact
        if (_currentlyPlayingArtifact.value?.id == artifact.id) {
            audioPlayer.togglePlayPause()
        } else {
            _currentlyPlayingArtifact.value = artifact
            
            // If it was unfinished, start from the last position
            val startPos = if (feedArtifact.isUnfinished) feedArtifact.lastPositionMs else 0L
            reviewSessionManager.startListening(artifact)
            if (startPos > 0) {
                audioPlayer.seekTo(startPos)
            }
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            combine(currentPosition, isPlaying, _currentlyPlayingArtifact) { pos, playing, artifact ->
                Triple(pos, playing, artifact)
            }.collect { (pos, playing, artifact) ->
                if (playing && artifact != null) {
                    updateSession(artifact, pos)
                }
            }
        }
    }

    private fun updateSession(artifact: Artifact, position: Long) {
        val userId = authRepository.currentUser.value?.uid ?: return
        viewModelScope.launch {
            feedRepository.updateListeningSession(
                ListeningSession(
                    userId = userId,
                    artifactId = artifact.id,
                    lastPositionMs = position,
                    totalDurationMs = artifact.duration * 1000L,
                    isCompleted = position >= (artifact.duration * 1000L * 0.95f) // 95% completion
                )
            )
        }
    }

    fun deleteArtifact(artifactId: String) {
        viewModelScope.launch {
            artifactRepository.deletePublishedArtifact(artifactId)
                .onSuccess {
                    loadFeed() // Refresh feed after deletion
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // audioPlayer.stop() // Optional: depends if we want background play
    }
}
