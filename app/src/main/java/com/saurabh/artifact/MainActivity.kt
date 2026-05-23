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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.ui.theme.LocalStartupStage
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.saurabh.artifact.navigation.NavGraph
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.audio.UploadSessionManager
import com.saurabh.artifact.ui.player.ArtifactPlayerView
import com.saurabh.artifact.ui.recording.components.MiniRecorder
import com.saurabh.artifact.ui.components.AmbientUploadBar
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
    val userProfile by mainViewModel.currentUserProfile.collectAsStateWithLifecycle()

    Log.d("PERF_DEBUG", "AppRoot Recomposed. Stage: $stage")

    val uploadSessionManager = dagger.hilt.android.EntryPointAccessors.fromActivity(
        androidx.compose.ui.platform.LocalContext.current as android.app.Activity,
        MainActivityEntryPoint::class.java
    ).uploadSessionManager()

    CompositionLocalProvider(
        LocalStartupStage provides stage,
        com.saurabh.artifact.ui.theme.LocalUserProfile provides userProfile
    ) {
        // Defer more expensive state collection until we are past Presence
        AuthenticatedIsland(
            stage = stage,
            mainViewModel = mainViewModel,
            recordingSessionManager = recordingSessionManager,
            uploadSessionManager = uploadSessionManager,
            onboardingManager = onboardingManager
        )
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.android.components.ActivityComponent::class)
interface MainActivityEntryPoint {
    fun uploadSessionManager(): UploadSessionManager
}

@Composable
fun AuthenticatedIsland(
    stage: StartupStage,
    mainViewModel: MainViewModel,
    recordingSessionManager: RecordingSessionManager,
    uploadSessionManager: UploadSessionManager,
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
                    
                    // Critical: Review screen MUST have absolute ownership.
                    // If we are on RecordingReview, we COMPLETELY unmount the PublicPlayer.
                    val screensWithoutOverlays = listOf(
                        Screen.InstantRecord.route,
                        Screen.PreRecordingWarning.route,
                        Screen.RecordingReview.route, // Added strictly here
                        Screen.PublishPreparation.route,
                        Screen.PublishApproval.route,
                        Screen.IdentitySelection.route,
                        Screen.PresenceBuilder.route,
                        Screen.PostRecordingDecision.route // Also added
                    )
                    val showOverlays = currentRoute != null && currentRoute !in screensWithoutOverlays

                    if (showOverlays) {
                        val uploadSession by uploadSessionManager.currentSession.collectAsStateWithLifecycle()
                        
                        Column(
                            modifier = androidx.compose.ui.Modifier
                                .align(androidx.compose.ui.Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .zIndex(1000f) // Highest priority for essential overlays
                        ) {
                            AmbientUploadBar(
                                session = uploadSession,
                                onDismiss = { uploadSessionManager.dismissSession() }
                            )

                            MiniRecorder(
                                status = recordingState.status,
                                durationSeconds = recordingState.durationSeconds,
                                onClick = {
                                    navController.navigate(Screen.InstantRecord.route) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                    }

                    // Debug Interaction Highlight Layer
                    // Only active in debug builds to visualize touch interception zones.
                    if (BuildConfig.DEBUG && false) { // Toggle to true to see invisible boxes
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Red.copy(alpha = 0.05f))
                                .padding(16.dp)
                        ) {
                            Text(
                                "DEBUG: Interaction Layer Active",
                                color = Color.Red,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    // Only render heavy media UI when in RITUAL stage or later
                    if (stage >= StartupStage.RITUAL) {
                        var reportingArtifactId by remember { mutableStateOf<String?>(null) }
                        
                        ArtifactPlayerView(
                            isVisible = showOverlays,
                            onNavigateToDraftEdit = { draftId ->
                                navController.navigate(Screen.RecordingReview.createRoute(draftId))
                            },
                            onNavigateToPublish = { draftId ->
                                navController.navigate(Screen.PublishPreparation.createRoute(draftId))
                            },
                            onNavigateToComments = { artifactId, userId ->
                                navController.navigate(Screen.Comments.createRoute(artifactId, userId))
                            },
                            onReportArtifact = { reportingArtifactId = it }
                        )

                        if (reportingArtifactId != null) {
                            val feedViewModel: com.saurabh.artifact.ui.feed.FeedViewModel = hiltViewModel()
                            com.saurabh.artifact.ui.components.moderation.ReportSheet(
                                onReportSubmitted = { reason, details ->
                                    reportingArtifactId?.let { id ->
                                        feedViewModel.reportArtifact(id, reason, details)
                                    }
                                    reportingArtifactId = null
                                },
                                onDismiss = { reportingArtifactId = null }
                            )
                        }
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
