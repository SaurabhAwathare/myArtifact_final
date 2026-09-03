package com.saurabh.artifact

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.saurabh.artifact.audio.RecordingSessionManager
import com.saurabh.artifact.diagnostics.DiagnosticCategory
import com.saurabh.artifact.model.PlaybackSource
import com.saurabh.artifact.navigation.IncomingArtifact
import com.saurabh.artifact.navigation.NavGraph
import com.saurabh.artifact.navigation.PublishingStudio
import com.saurabh.artifact.startup.StartupStage
import com.saurabh.artifact.ui.components.GlobalOverlayHost
import com.saurabh.artifact.ui.components.moderation.ReportSheet
import com.saurabh.artifact.ui.feed.FeedViewModel
import com.saurabh.artifact.ui.player.PlayerViewModel
import com.saurabh.artifact.ui.splash.SplashUI
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.diagnostics.DiagnosticLogger
import com.saurabh.artifact.navigation.Route
import com.saurabh.artifact.startup.SecurityStatus
import com.saurabh.artifact.ui.recovery.MnemonicRestoreScreen
import com.saurabh.artifact.ui.recovery.RescueScreen
import com.saurabh.artifact.ui.splash.StartupErrorScreen
import com.saurabh.artifact.util.OnboardingManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var recordingSessionManager: RecordingSessionManager

    @Inject
    lateinit var onboardingManager: OnboardingManager

    @Inject
    lateinit var diagnosticLogger: DiagnosticLogger

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // INTENT GUARD: Only process launch intent on fresh creation.
        // Rotation/Recreation will have savedInstanceState != null and shouldn't re-deliver the intent.
        if (savedInstanceState == null) {
            mainViewModel.onLaunchIntent(intent)
        }

        setContent {
            val startupState by mainViewModel.startupState.collectAsStateWithLifecycle()
            val stage by mainViewModel.startupStage.collectAsStateWithLifecycle()
            val securityStatus by mainViewModel.securityStatus.collectAsStateWithLifecycle()

            val isSecureFlagRequired by mainViewModel.isSecureFlagRequired.collectAsStateWithLifecycle()

            LaunchedEffect(isSecureFlagRequired) {
                if (isSecureFlagRequired) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            ArtifactTheme(logger = diagnosticLogger) {
                AppRoot(
                    startupState = startupState,
                    stage = stage,
                    securityStatus = securityStatus,
                    mainViewModel = mainViewModel,
                    recordingSessionManager = recordingSessionManager,
                    onboardingManager = onboardingManager,
                    diagnosticLogger = diagnosticLogger,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Genuine new intent while app is running (Warm Start delivery)
        mainViewModel.onLaunchIntent(intent)
    }
}

@Composable
fun AppRoot(
    startupState: AppStartupState,
    stage: StartupStage,
    securityStatus: SecurityStatus,
    mainViewModel: MainViewModel,
    recordingSessionManager: RecordingSessionManager,
    onboardingManager: OnboardingManager,
    diagnosticLogger: DiagnosticLogger
) {
    LaunchedEffect(startupState) {
        diagnosticLogger.debug(DiagnosticCategory.STARTUP, "STARTUP_STATE_CHANGED", mapOf("state" to startupState.javaClass.simpleName, "security" to securityStatus.name))
    }

    LaunchedEffect(Unit) {
        mainViewModel.start()
    }

    when (startupState) {
        is AppStartupState.Rescue -> {
            val view = LocalView.current
            RescueScreen(
                onRestart = {
                    val activity = (view.context as Activity)
                    val intent = activity.intent
                    activity.finish()
                    activity.startActivity(intent)
                }
            )
        }
        is AppStartupState.Recovery -> {
            MnemonicRestoreScreen(
                onSuccess = {
                    mainViewModel.retryStartup()
                },
                onStartFresh = {
                    mainViewModel.retryStartup()
                }
            )
        }
        is AppStartupState.Ready -> {
            val startDestination = startupState.startDestination
            val playerViewModel: PlayerViewModel = hiltViewModel()

            key(startDestination) {
                val navController = rememberNavController()

                LaunchedEffect(navController, startupState.startupAction) {
                    // 1. Synchronous execution of startup action (if any)
                    startupState.startupAction?.let { action ->
                        when (action) {
                            is IncomingArtifact -> {
                                playerViewModel.playArtifactById(
                                    action.artifactId, 
                                    action.source
                                )
                            }
                            is Route -> {
                                navController.navigate(action) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    }

                    // 2. Collection of post-startup navigation events (Warm Start)
                    mainViewModel.navigationEvent.collect { event ->
                        when (event) {
                            is IncomingArtifact -> {
                                playerViewModel.playArtifactById(
                                    event.artifactId, 
                                    event.source
                                )
                            }
                            else -> {
                                navController.navigate(event as Route) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    val reportingArtifactId by mainViewModel.reportingArtifactId.collectAsStateWithLifecycle()

                    GlobalOverlayHost(
                        navController = navController,
                        recordingSessionManager = recordingSessionManager,
                        onNavigateToDraftEdit = { draftId ->
                            navController.navigate(PublishingStudio(draftId)) { launchSingleTop = true }
                        },
                        onNavigateToPublish = { draftId ->
                            navController.navigate(PublishingStudio(draftId)) { launchSingleTop = true }
                        },
                        onReportArtifact = { mainViewModel.showReportSheet(it) },
                        playerViewModel = playerViewModel,
                        stage = stage
                    ) {
                        NavGraph(
                            navController = navController,
                            startDestination = startDestination,
                            recordingSessionManager = recordingSessionManager,
                            onboardingManager = onboardingManager,
                            onReportArtifact = { mainViewModel.showReportSheet(it) },
                            onPlayArtifactById = { artifactId ->
                                // Phase 12: Context Preservation - Only trigger playback if the artifact isn't already active.
                                // This prevents overwriting the source during transient startup navigation collisions.
                                val state = playerViewModel.uiState.value
                                val isAlreadyActive = (state.currentArtifact?.id == artifactId || state.currentPlayableArtifact?.id == artifactId)
                                
                                if (!isAlreadyActive) {
                                    // Default to DEEP_LINK for generic ID-based play requests from OS level if not specified
                                    playerViewModel.playArtifactById(artifactId, PlaybackSource.DEEP_LINK)
                                } else {
                                    playerViewModel.setExpanded(true)
                                }
                            },
                            playerViewModel = playerViewModel,
                            onDestinationChanged = { mainViewModel.updateSecurityStatus(it) },
                            diagnosticLogger = diagnosticLogger
                        )
                    }

                    if (reportingArtifactId != null && stage >= StartupStage.RITUAL) {
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
        is AppStartupState.Error -> {
            StartupErrorScreen(
                message = startupState.message,
                onRetry = { mainViewModel.retryStartup() }
            )
        }
        else -> {
            SplashUI(stage = stage)
        }
    }
}
