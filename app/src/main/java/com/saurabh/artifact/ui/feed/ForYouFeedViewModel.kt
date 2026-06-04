package com.saurabh.artifact.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.artifact.audio.PlaybackCoordinator
import com.saurabh.artifact.audio.PlaybackType
import com.saurabh.artifact.model.*
import com.saurabh.artifact.repository.AuthRepository
import com.saurabh.artifact.repository.FeedRepository
import com.saurabh.artifact.service.FeedComposer
import com.saurabh.artifact.ui.util.UiText
import com.saurabh.artifact.ui.util.ErrorMessageMapper
import com.saurabh.artifact.R
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
    val audioPlayer: PlaybackCoordinator,
    private val reviewSessionManager: com.saurabh.artifact.audio.ReviewSessionManager,
    private val reviewAuthorityService: com.saurabh.artifact.audio.ReviewAuthorityService
) : ViewModel() {

    private val _feedState = MutableStateFlow<FeedCompositionState>(FeedCompositionState.Loading)
    val feedState: StateFlow<FeedCompositionState> = _feedState.asStateFlow()

    private val _message = MutableSharedFlow<UiText>()
    val message = _message.asSharedFlow()

    val currentlyPlayingArtifact: StateFlow<Artifact?> = audioPlayer.currentArtifact

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
                val feedItems = feedComposer.composeFeed(userId)
                _feedState.value = FeedCompositionState.Success(feedItems)
                
                // HARDENING: Proactively pre-cache the first 3 artifacts to eliminate playback latency
                feedItems.take(3).forEach { feedItem ->
                    audioPlayer.preCache(feedItem.artifact)
                }
            } catch (e: Exception) {
                _feedState.value = FeedCompositionState.Error(UiText.StringResource(R.string.quiet_moment_interrupted))
                _message.emit(ErrorMessageMapper.map(e))
            }
        }
    }

    fun playArtifact(feedArtifact: FeedArtifact) {
        val artifact = feedArtifact.artifact
        
        // Validation: Ensure the artifact is playable before calling the player
        if (artifact.audioUrl.isEmpty()) {
            viewModelScope.launch {
                _message.emit(UiText.StringResource(R.string.no_voice_yet))
            }
            return
        }

        if (currentlyPlayingArtifact.value?.id == artifact.id) {
            audioPlayer.togglePlayPause()
        } else {
            // If it was unfinished, start from the last position
            val startPos = if (feedArtifact.isUnfinished) feedArtifact.lastPositionMs else 0L
            
            // Extract the relevant collection from the current feed state
            val collection = when (val state = feedState.value) {
                is FeedCompositionState.Success -> state.items.map { it.artifact }
                else -> emptyList()
            }
            
            audioPlayer.playArtifact(artifact, collection, startPos)
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            combine(currentPosition, isPlaying, currentlyPlayingArtifact) { pos, playing, artifact ->
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
        val currentProgress = reviewAuthorityService.currentProgress.value
        val isValidated = currentProgress?.artifactId == artifact.id && currentProgress.isValidationMet
        
        viewModelScope.launch {
            feedRepository.updateListeningSession(
                ListeningSession(
                    userId = userId,
                    artifactId = artifact.id,
                    lastPositionMs = position,
                    totalDurationMs = artifact.durationMs,
                    isCompleted = isValidated
                )
            )
        }
    }

    fun deleteArtifact(artifactId: String) {
        viewModelScope.launch {
            artifactRepository.deletePublishedArtifact(artifactId)
                .onSuccess {
                    // Optimistic UI: Remove from the current feed state immediately
                    val currentState = _feedState.value
                    if (currentState is FeedCompositionState.Success) {
                        val updatedItems = currentState.items.filter { it.artifact.id != artifactId }
                        _feedState.value = FeedCompositionState.Success(updatedItems)
                    }
                    _message.emit(UiText.StringResource(R.string.reflection_deleted))
                }
                .onFailure { e ->
                    _message.emit(ErrorMessageMapper.map(e))
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // audioPlayer.stop() // Optional: depends if we want background play
    }
}
