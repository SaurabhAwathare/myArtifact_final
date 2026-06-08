package com.saurabh.artifact

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.saurabh.artifact.audio.PublishStateManager
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.navigation.NavGraph
import com.saurabh.artifact.navigation.Screen
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.ui.components.GlobalOverlayHost
import com.saurabh.artifact.ui.components.moderation.ReportSheet
import com.saurabh.artifact.ui.feed.FeedViewModel
import com.saurabh.artifact.ui.player.PlayerViewModel
import com.saurabh.artifact.ui.splash.SplashUI
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.LocalStartupStage
import com.saurabh.artifact.ui.theme.LocalUserProfile
import com.saurabh.artifact.util.OnboardingManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var recordingSessionManager: RecordingSessionManager

    @Inject
    lateinit var onboardingManager: OnboardingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        println("ReviewDebug: MainActivity onCreate - println test")
        Log.d("ReviewDebug", "MainActivity onCreate - APP STARTED")
        
        // Ensure logs are flushed
        Log.i("ReviewDebug", "Checking if log level INFO works")
        Log.d("APP_FLOW", "1. MainActivity.onCreate")
        
        checkNotificationPermission()
        
        mainViewModel.onNewIntent(intent)
        
        // Begin deterministic initialization
        mainViewModel.start()
        
        // enableEdgeToEdge() // Temporarily disabled to fix black screen issues
        
        observeStealthMode()

        setContent {
            Log.d("APP_FLOW", "2. Compose Root setContent")
            
            ArtifactTheme {
                AppRoot(
                    mainViewModel = mainViewModel, 
                    recordingSessionManager = recordingSessionManager,
                    onboardingManager = onboardingManager
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        mainViewModel.onNewIntent(intent)
    }

    private fun observeStealthMode() {
        lifecycleScope.launch {
            mainViewModel.isStealthModeEnabled.collect { isEnabled ->
                if (isEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                    Log.d("Notifications", "POST_NOTIFICATIONS granted: $isGranted")
                }.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun AppRoot(
    mainViewModel: MainViewModel,
    recordingSessionManager: RecordingSessionManager,
    onboardingManager: OnboardingManager
) {
    // Only collect the essential stage at the root
    val stage by mainViewModel.startupStage.collectAsStateWithLifecycle()
    val userProfile by mainViewModel.currentUserProfile.collectAsStateWithLifecycle()

    Log.d("PERF_DEBUG", "AppRoot Recomposed. Stage: $stage")

    val activity = LocalActivity.current ?: return
    val publishStateManager = EntryPointAccessors.fromActivity(
        activity,
        MainActivityEntryPoint::class.java
    ).publishStateManager()

    CompositionLocalProvider(
        LocalStartupStage provides stage,
        LocalUserProfile provides userProfile
    ) {
        // Defer more expensive state collection until we are past Presence
        AuthenticatedIsland(
            stage = stage,
            mainViewModel = mainViewModel,
            recordingSessionManager = recordingSessionManager,
            publishStateManager = publishStateManager,
            onboardingManager = onboardingManager
        )
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.android.components.ActivityComponent::class)
interface MainActivityEntryPoint {
    fun publishStateManager(): PublishStateManager
}

@Composable
fun AuthenticatedIsland(
    stage: StartupStage,
    mainViewModel: MainViewModel,
    recordingSessionManager: RecordingSessionManager,
    publishStateManager: PublishStateManager,
    onboardingManager: OnboardingManager
) {
    val startupState by mainViewModel.startupState.collectAsStateWithLifecycle()
    val playerViewModel: PlayerViewModel = hiltViewModel()

    // Debug logging for startup state
    LaunchedEffect(startupState) {
        Log.d("STARTUP_DEBUG", "startupState = $startupState")
    }

    when (startupState) {
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
                        onPlayArtifactById = { playerViewModel.playArtifactById(it) }
                    )

                    // Global Overlay Management
                    if (stage >= StartupStage.RITUAL) {
                        val reportingArtifactId by mainViewModel.reportingArtifactId.collectAsStateWithLifecycle()
                        
                        GlobalOverlayHost(
                            navController = navController,
                            recordingSessionManager = recordingSessionManager,
                            publishStateManager = publishStateManager,
                            onNavigateToDraftEdit = { draftId ->
                                navController.navigate(Screen.RecordingReview.createRoute(draftId))
                            },
                            onNavigateToPublish = { draftId ->
                                navController.navigate(Screen.PublishPreparation.createRoute(draftId))
                            },
                            onNavigateToComments = { artifactId, userId ->
                                navController.navigate(Screen.Comments.createRoute(artifactId, userId))
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
