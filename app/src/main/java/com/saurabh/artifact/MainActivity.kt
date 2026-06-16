package com.saurabh.artifact

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.navigation.Comments
import com.saurabh.artifact.navigation.NavGraph
import com.saurabh.artifact.navigation.PublishingStudio
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.ui.components.GlobalOverlayHost
import com.saurabh.artifact.ui.components.moderation.ReportSheet
import com.saurabh.artifact.ui.feed.FeedViewModel
import com.saurabh.artifact.ui.player.PlayerViewModel
import com.saurabh.artifact.model.PlaybackSource
import com.saurabh.artifact.ui.recovery.RescueScreen
import com.saurabh.artifact.ui.splash.SplashUI
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.LocalStartupStage
import com.saurabh.artifact.ui.theme.LocalUserProfile
import com.saurabh.artifact.util.OnboardingManager
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var recordingSessionManager: Lazy<RecordingSessionManager>

    @Inject
    lateinit var onboardingManager: Lazy<OnboardingManager>

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Hold the splash screen until we know where to go (Auth ready)
        splashScreen.setKeepOnScreenCondition {
            mainViewModel.startupState.value is AppStartupState.Initializing
        }

        println("ReviewDebug: MainActivity onCreate - println test")
        Log.d("ReviewDebug", "MainActivity onCreate - APP STARTED")
        
        // SIMULATE CRASH LOOP for verification
        // throw RuntimeException("Test Crash for Rescue Mode")

        // Ensure logs are flushed
        Log.i("ReviewDebug", "Checking if log level INFO works")
        Log.d("APP_FLOW", "1. MainActivity.onCreate")
        
        mainViewModel.onNewIntent(intent)
        
        // Begin deterministic initialization
        mainViewModel.start()
        
        enableEdgeToEdge()
        
        observeSecurityFlags()

        setContent {
            Log.d("APP_FLOW", "2. Compose Root setContent")
            
            ArtifactTheme {
                AppRoot(
                    mainViewModel = mainViewModel, 
                    recordingSessionManager = recordingSessionManager.get(),
                    onboardingManager = onboardingManager.get(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mainViewModel.onNewIntent(intent)
    }

    private fun observeSecurityFlags() {
        lifecycleScope.launch {
            mainViewModel.isSecureFlagRequired.collect { isRequired ->
                if (isRequired) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

@Composable
fun AppRoot(
    mainViewModel: MainViewModel,
    recordingSessionManager: RecordingSessionManager,
    onboardingManager: OnboardingManager,
) {
    // Only collect the essential stage at the root
    val stage by mainViewModel.startupStage.collectAsStateWithLifecycle()
    val userProfile by mainViewModel.currentUserProfile.collectAsStateWithLifecycle()

    SideEffect {
        Log.d("PERF_DEBUG", "AppRoot Recomposed. Stage: $stage")
    }

    CompositionLocalProvider(
        LocalStartupStage provides stage,
        LocalUserProfile provides userProfile
    ) {
        // Defer more expensive state collection until we are past Presence
        AuthenticatedIsland(
            stage = stage,
            mainViewModel = mainViewModel,
            recordingSessionManager = recordingSessionManager,
            onboardingManager = onboardingManager
        )
    }
}

@Composable
fun AuthenticatedIsland(
    stage: StartupStage,
    mainViewModel: MainViewModel,
    recordingSessionManager: RecordingSessionManager,
    onboardingManager: OnboardingManager
) {
    val startupState by mainViewModel.startupState.collectAsStateWithLifecycle()
    val playerViewModel: PlayerViewModel = hiltViewModel()

    // Debug logging for startup state
    LaunchedEffect(startupState) {
        Log.d("STARTUP_DEBUG", "startupState = $startupState")
    }

    when (startupState) {
        is AppStartupState.Rescue -> {
            val view = LocalView.current
            RescueScreen(
                onRestart = {
                    // Trigger a process restart
                    val activity = (view.context as Activity)
                    val intent = activity.intent
                    activity.finish()
                    activity.startActivity(intent)
                }
            )
        }
        is AppStartupState.Ready -> {
            val readyState = startupState as AppStartupState.Ready
            val startDestination = readyState.startDestination

            key(startDestination) {
                val navController = rememberNavController()

                // Observe navigation events
                LaunchedEffect(navController) {
                    mainViewModel.navigationEvent.collect { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    NavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        recordingSessionManager = recordingSessionManager,
                        onboardingManager = onboardingManager,
                        onReportArtifact = { mainViewModel.showReportSheet(it) },
                        onPlayArtifactById = { playerViewModel.playArtifactById(it, PlaybackSource.FEED_PLAYBACK) },
                        playerViewModel = playerViewModel,
                        onDestinationChanged = { mainViewModel.updateSecurityStatus(it) }
                    )

                    // Global Overlay Management
                    if (stage >= StartupStage.RITUAL) {
                        val reportingArtifactId by mainViewModel.reportingArtifactId.collectAsStateWithLifecycle()
                        
                        GlobalOverlayHost(
                            navController = navController,
                            recordingSessionManager = recordingSessionManager,
                            onNavigateToDraftEdit = { draftId ->
                                navController.navigate(PublishingStudio(draftId))
                            },
                            onNavigateToPublish = { draftId ->
                                navController.navigate(PublishingStudio(draftId))
                            },
                            onNavigateToComments = { artifactId, userId ->
                                navController.navigate(Comments(artifactId, userId))
                            },
                            onReportArtifact = { mainViewModel.showReportSheet(it) },
                            playerViewModel = playerViewModel
                        )

                        if (reportingArtifactId != null) {
                            val feedViewModel: FeedViewModel = hiltViewModel()
                            ReportSheet(
                                onReportSubmitted = { reason, details ->
                                    reportingArtifactId?.let { id ->
                                        feedViewModel.reportArtifact(id, reason, details)
                                    }
                                    mainViewModel.dismissReportSheet()
                                },
                                onDismiss = { mainViewModel.dismissReportSheet() }
                            )
                        }
                    }
                }
            }
        }
        else -> {
            SplashUI()
        }
    }
}
