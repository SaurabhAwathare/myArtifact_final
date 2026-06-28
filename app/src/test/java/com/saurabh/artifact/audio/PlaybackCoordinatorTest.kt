package com.saurabh.artifact.audio

import com.saurabh.artifact.model.Artifact
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {
    private val playbackSessionManager = mockk<PlaybackSessionManager>(relaxed = true)
    private val reviewSessionManager = mockk<ReviewSessionManager>(relaxed = true)
    private val reviewAuthorityService = mockk<ReviewAuthorityService>(relaxed = true)
    private val transientPlayerManager = mockk<TransientPlayerManager>(relaxed = true)
    private val analytics = mockk<PlaybackAnalyticsManager>(relaxed = true)

    private lateinit var coordinator: PlaybackCoordinator
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Mocking flows in managers
        every { playbackSessionManager.currentArtifact } returns MutableStateFlow(null)
        every { playbackSessionManager.isPlaying } returns MutableStateFlow(false)
        every { playbackSessionManager.isBuffering } returns MutableStateFlow(false)
        every { playbackSessionManager.currentPosition } returns MutableStateFlow(0L)
        every { playbackSessionManager.durationMs } returns MutableStateFlow(0L)
        every { playbackSessionManager.playbackSpeed } returns MutableStateFlow(1.0f)
        every { playbackSessionManager.isSkipSilenceEnabled } returns MutableStateFlow(false)
        every { playbackSessionManager.activePlayback } returns MutableStateFlow(null)
        every { playbackSessionManager.positionSync } returns MutableStateFlow(PlaybackSessionManager.PositionSync(0, 0, 1.0f, false))
        every { playbackSessionManager.playbackCompletedEvent } returns MutableStateFlow("")
        every { playbackSessionManager.error } returns MutableStateFlow("")

        coordinator = PlaybackCoordinator(
            playbackSessionManager, reviewSessionManager, reviewAuthorityService, transientPlayerManager, analytics
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `playArtifact should call playbackSessionManager play`() {
        val artifact = Artifact(id = "art1")
        coordinator.playArtifact(artifact)
        verify { playbackSessionManager.play(artifact, any(), 0L, any()) }
    }

    @Test
    fun `togglePlayPause should call playbackSessionManager togglePlayPause`() {
        coordinator.togglePlayPause()
        verify { playbackSessionManager.togglePlayPause() }
    }

    @Test
    fun `stop should stop both main and transient playback`() {
        coordinator.stop()
        verify { playbackSessionManager.stop() }
        verify { transientPlayerManager.stop() }
    }

    @Test
    fun `startSleepTimer should stop playback when timer reaches zero`() = runTest {
        val isPlayingFlow = MutableStateFlow(true)
        every { playbackSessionManager.isPlaying } returns isPlayingFlow
        
        coordinator.startSleepTimer(1.seconds)
        
        advanceTimeBy(2000) 
        
        verify { playbackSessionManager.stop() }
    }
}
