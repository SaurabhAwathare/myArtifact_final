package com.saurabh.artifact

import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.ui.theme.LocalStartupStage
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.saurabh.artifact.navigation.NavGraph
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.ui.player.ArtifactPlayerView
import com.saurabh.artifact.ui.recording.components.MiniRecorder
import com.saurabh.artifact.navigation.Screen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var recordingSessionManager: RecordingSessionManager

    @Inject
    lateinit var onboardingManager: com.saurabh.artifact.util.OnboardingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        println("ReviewDebug: MainActivity onCreate - println test")
        android.util.Log.d("ReviewDebug", "MainActivity onCreate - APP STARTED")
        
        // Ensure logs are flushed
        android.util.Log.i("ReviewDebug", "Checking if log level INFO works")
        Log.d("APP_FLOW", "1. MainActivity.onCreate")
        
        checkNotificationPermission()
        
        mainViewModel.onNewIntent(intent)
        
        // Begin deterministic initialization
        mainViewModel.start()
        
        // enableEdgeToEdge() // Temporarily disabled to fix black screen issues
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        mainViewModel.onNewIntent(intent)
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
    onboardingManager: com.saurabh.artifact.util.OnboardingManager
) {
    // Only collect the essential stage at the root
    val stage by mainViewModel.startupStage.collectAsStateWithLifecycle()

    Log.d("PERF_DEBUG", "AppRoot Recomposed. Stage: $stage")

    CompositionLocalProvider(LocalStartupStage provides stage) {
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
    onboardingManager: com.saurabh.artifact.util.OnboardingManager
) {
    val startupState by mainViewModel.startupState.collectAsStateWithLifecycle()

    // Debug logging for startup state
    androidx.compose.runtime.LaunchedEffect(startupState) {
        Log.d("STARTUP_DEBUG", "startupState = $startupState")
    }

    when (startupState) {
        is AppStartupState.Ready -> {
            val readyState = startupState as AppStartupState.Ready
            val startDestination = readyState.startDestination

            androidx.compose.runtime.key(startDestination) {
                val navController = rememberNavController()

                // Observe navigation events
                androidx.compose.runtime.LaunchedEffect(navController) {
                    mainViewModel.navigationEvent.collect { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                    }
                }

                Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    NavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        recordingSessionManager = recordingSessionManager,
                        onboardingManager = onboardingManager
                    )

                    // Mini Recorder (Floating Trust Indicator)
                    val recordingState by recordingSessionManager.recordingState.collectAsStateWithLifecycle()
                    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
                    
                    val screensWithoutOverlays = listOf(
                        Screen.InstantRecord.route,
                        Screen.PreRecordingWarning.route,
                        Screen.DraftEdit.route,
                        Screen.RecordingReview.route,
                        Screen.PublishApproval.route,
                        Screen.IdentitySelection.route,
                        Screen.AvatarCreator.route
                    )
                    val showOverlays = currentRoute !in screensWithoutOverlays

                    if (showOverlays) {
                        MiniRecorder(
                            status = recordingState.status,
                            durationSeconds = recordingState.durationSeconds,
                            onClick = {
                                navController.navigate(Screen.InstantRecord.route) {
                                    launchSingleTop = true
                                }
                            },
                            modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                        )
                    }

                    // Only render heavy media UI when in RITUAL stage or later
                    if (stage >= StartupStage.RITUAL) {
                        ArtifactPlayerView(
                            isVisible = showOverlays,
                            onNavigateToDraftEdit = { filePath ->
                                navController.navigate(Screen.DraftEdit.createRoute(filePath))
                            },
                            onNavigateToPublish = { draftId ->
                                navController.navigate(Screen.PublishApproval.createRoute(draftId))
                            }
                        )
                    }
                }
            }
        }
        else -> {
            com.saurabh.artifact.ui.splash.SplashUI()
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen() {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.CenterAlignedTopAppBar(
                title = {
                    com.saurabh.artifact.ui.components.BrandTitle(
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        modifier = androidx.compose.ui.Modifier.alpha(0.5f)
                    )
                },
                colors = androidx.compose.material3.TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
        )
    }
}

@Composable
fun BootScreen() {
    Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text = "Artifact",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = androidx.compose.ui.graphics.Color.White
        )
    }
}
