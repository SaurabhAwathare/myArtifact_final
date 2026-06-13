package com.saurabh.artifact.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.saurabh.artifact.ui.profile.components.ProfileHeader
import com.saurabh.artifact.ui.profile.components.draftSection
import com.saurabh.artifact.ui.profile.components.userArtifactsList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userId: String? = null,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onEditIdentity: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToReview: (String) -> Unit,
    onNavigateToPublish: (String) -> Unit = {},
    onNavigateToResonanceList: (String, String, String) -> Unit = { _, _, _ -> },
    onNavigateToComments: (String, String) -> Unit = { _, _ -> },
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedIds by viewModel.savedIds.collectAsStateWithLifecycle(emptySet())
    val showLogoutDialog = remember { mutableStateOf(false) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(userId) {
        viewModel.setTargetUser(userId)
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { uiText ->
            snackbarHostState.showSnackbar(uiText.asString(context))
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.logoutState) {
        when (val logoutState = uiState.logoutState) {
            is LogoutState.Success -> {
                onLogout()
                viewModel.resetLogoutState()
            }
            is LogoutState.Error -> {
                snackbarHostState.showSnackbar(logoutState.message.asString(context))
                viewModel.resetLogoutState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        if (uiState.isSelf) "My Journey" else uiState.userProfile?.anonymousName ?: "Profile",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isSelf) {
                        IconButton(onClick = { showLogoutDialog.value = true }) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Sign Out")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (uiState.isLoading || uiState.isActionLoading) {
                        item {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                        }
                    }

                    item {
                        ProfileHeader(
                            user = uiState.userProfile,
                            avatarConfig = uiState.avatarConfig,
                            isSelf = uiState.isSelf,
                            isResonating = uiState.isResonating,
                            onResonateClick = { viewModel.toggleResonance() },
                            onEditClick = onEditIdentity,
                            onResonatorsClick = {
                                uiState.userProfile?.id?.let { id ->
                                    onNavigateToResonanceList(id, "resonance_in", "Resonators")
                                }
                            },
                            onResonatingClick = {
                                uiState.userProfile?.id?.let { id ->
                                    onNavigateToResonanceList(id, "resonance_out", "Resonating")
                                }
                            }
                        )
                    }

                    item {
                        SecondaryTabRow(
                            selectedTabIndex = uiState.selectedTab.ordinal,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary,
                            divider = {},
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            ProfileTab.entries.forEach { tab ->
                                val isTabVisible = when (tab) {
                                    ProfileTab.PUBLISHED -> true
                                    ProfileTab.DRAFTS -> uiState.isSelf
                                    ProfileTab.SAVED -> uiState.isSelf
                                }
                                
                                if (isTabVisible) {
                                    Tab(
                                        selected = uiState.selectedTab == tab,
                                        onClick = { viewModel.selectTab(tab) },
                                        text = { 
                                            Text(
                                                tab.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = if (uiState.selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            ) 
                                        }
                                    )
                                }
                            }
                        }
                    }

                    when (uiState.selectedTab) {
                        ProfileTab.PUBLISHED -> {
                            userArtifactsList(
                                artifacts = uiState.publishedArtifacts,
                                currentlyPlayingArtifact = uiState.currentlyPlayingArtifact,
                                isPlaying = uiState.isPlaying,
                                isBuffering = uiState.isBuffering,
                                currentPosition = uiState.currentPosition,
                                duration = uiState.durationMs,
                                isSelf = uiState.isSelf,
                                currentUserId = viewModel.currentUserId,
                                savedIds = savedIds,
                                onPlayClick = { viewModel.playAudio(it) },
                                onRename = { artifact, newTitle -> 
                                    viewModel.renamePublishedArtifact(artifact.id, newTitle)
                                },
                                onDelete = { artifact -> 
                                    viewModel.deletePublishedArtifact(artifact.id)
                                },
                                onSaveClick = { viewModel.toggleSave(it) },
                                onViewComments = { artifact -> 
                                    onNavigateToComments(artifact.id, artifact.userId)
                                },
                                emptyMessage = if (uiState.isSelf) "You haven't shared any reflections yet." else "This journey is just beginning."
                            )
                        }
                        ProfileTab.DRAFTS -> {
                            if (uiState.isSelf) {
                                draftSection(
                                    drafts = uiState.localDrafts,
                                    currentlyPlayingId = uiState.currentlyPlayingArtifact?.id,
                                    isPlaying = uiState.isPlaying,
                                    isBuffering = uiState.isBuffering,
                                    onPlayClick = { draft -> onNavigateToReview(draft.id) },
                                    onRename = { draft, newTitle -> 
                                        viewModel.renameDraft(draft.id, newTitle)
                                    },
                                    onPublishClick = { draft -> onNavigateToPublish(draft.id) },
                                    onDelete = { draft -> 
                                        viewModel.deleteDraft(draft.id)
                                    }
                                )

                                if (uiState.cloudDrafts.isNotEmpty()) {
                                    userArtifactsList(
                                        artifacts = uiState.cloudDrafts,
                                        currentlyPlayingArtifact = uiState.currentlyPlayingArtifact,
                                        isPlaying = uiState.isPlaying,
                                        isBuffering = uiState.isBuffering,
                                        currentPosition = uiState.currentPosition,
                                        duration = uiState.durationMs,
                                        isSelf = true,
                                        currentUserId = viewModel.currentUserId,
                                        onPlayClick = { viewModel.playAudio(it) },
                                        onRename = { artifact, newTitle -> 
                                            viewModel.renamePublishedArtifact(artifact.id, newTitle)
                                        },
                                        onDelete = { artifact -> 
                                            viewModel.deletePublishedArtifact(artifact.id)
                                        },
                                        onViewComments = { artifact -> 
                                            onNavigateToComments(artifact.id, artifact.userId)
                                        }
                                    )
                                }
                            }
                        }
                        ProfileTab.SAVED -> {
                            userArtifactsList(
                                artifacts = uiState.savedArtifacts,
                                currentlyPlayingArtifact = uiState.currentlyPlayingArtifact,
                                isPlaying = uiState.isPlaying,
                                isBuffering = uiState.isBuffering,
                                currentPosition = uiState.currentPosition,
                                duration = uiState.durationMs,
                                isSelf = uiState.isSelf,
                                currentUserId = viewModel.currentUserId,
                                savedIds = savedIds,
                                onPlayClick = { viewModel.playAudio(it) },
                                onRename = { artifact, newTitle -> 
                                    viewModel.renamePublishedArtifact(artifact.id, newTitle)
                                },
                                onDelete = { artifact -> 
                                    viewModel.deletePublishedArtifact(artifact.id)
                                },
                                onSaveClick = { viewModel.toggleSave(it) },
                                onViewComments = { artifact -> 
                                    onNavigateToComments(artifact.id, artifact.userId)
                                },
                                emptyMessage = "Moments that resonate with you will stay here."
                            )
                        }
                    }

                    item { Spacer(modifier = Modifier.height(100.dp)) }
                }
            }
        }
    }

    if (showLogoutDialog.value) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog.value = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to leave this session?") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog.value = false
                        viewModel.logout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog.value = false }) {
                    Text("Stay")
                }
            }
        )
    }
}
