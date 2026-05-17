package com.saurabh.artifact.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.ui.components.BottomPlayer
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
    onNavigateToDraftEdit: (String) -> Unit,
    onNavigateToComments: (String, String) -> Unit = { _, _ -> },
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val artifacts by viewModel.publishedArtifacts.collectAsState()
    val cloudDrafts by viewModel.cloudDrafts.collectAsState()
    val likedArtifacts by viewModel.likedArtifacts.collectAsState()
    val drafts by viewModel.drafts.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val identityEmoji by viewModel.identityEmoji.collectAsState()
    val isSelf by viewModel.isSelf.collectAsState()
    val isFollowing by viewModel.isFollowing.collectAsState()
    
    val currentlyPlayingArtifact by viewModel.currentlyPlayingArtifact.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val logoutState by viewModel.logoutState.collectAsState()

    val showLogoutDialog = remember { mutableStateOf(false) }

    LaunchedEffect(userId) {
        viewModel.setTargetUser(userId)
    }

    LaunchedEffect(logoutState) {
        if (logoutState is LogoutState.Success) {
            onLogout()
            viewModel.resetLogoutState()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        if (isSelf) "My Journey" else userProfile?.displayName ?: "Profile", 
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSelf) {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                item {
                    ProfileHeader(
                        user = userProfile,
                        identityEmoji = identityEmoji,
                        postCount = artifacts.size,
                        isSelf = isSelf,
                        isFollowing = isFollowing,
                        onFollowClick = { viewModel.toggleFollow() },
                        onEditClick = onEditIdentity
                    )
                }

                item {
                    SecondaryTabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        divider = {},
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        ProfileTab.entries.forEach { tab ->
                            if (tab != ProfileTab.DRAFTS || isSelf) {
                                Tab(
                                    selected = selectedTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    text = { 
                                        Text(
                                            tab.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        ) 
                                    }
                                )
                            }
                        }
                    }
                }

                when (selectedTab) {
                    ProfileTab.PUBLISHED -> {
                        userArtifactsList(
                            artifacts = artifacts,
                            currentlyPlayingArtifact = currentlyPlayingArtifact,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            currentPosition = currentPosition,
                            duration = duration,
                            isSelf = isSelf,
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
                    ProfileTab.DRAFTS -> {
                        if (isSelf) {
                            draftSection(
                                drafts = drafts,
                                currentlyPlayingId = currentlyPlayingArtifact?.id,
                                isPlaying = isPlaying,
                                isBuffering = isBuffering,
                                onPlayClick = { draft -> viewModel.playDraft(draft) },
                                onRename = { draft, newTitle -> 
                                    viewModel.renameDraft(draft.id, newTitle)
                                },
                                onDelete = { draft -> 
                                    viewModel.deleteDraft(draft.id)
                                }
                            )

                            if (cloudDrafts.isNotEmpty()) {
                                userArtifactsList(
                                    artifacts = cloudDrafts,
                                    currentlyPlayingArtifact = currentlyPlayingArtifact,
                                    isPlaying = isPlaying,
                                    isBuffering = isBuffering,
                                    currentPosition = currentPosition,
                                    duration = duration,
                                    isSelf = isSelf,
                                    onPlayClick = { viewModel.playAudio(it) },
                                    onRename = { artifact, newTitle -> 
                                        viewModel.renamePublishedArtifact(artifact.id, newTitle)
                                    },
                                    onDelete = { artifact -> 
                                        viewModel.deletePublishedArtifact(artifact.id)
                                    },
                                    onViewComments = { artifact -> 
                                        onNavigateToComments(020993artifact.id, artifact.userId)
                                    }
                                )
                            }
                        }
                    }
                    ProfileTab.SAVED -> {
                        userArtifactsList(
                            artifacts = likedArtifacts,
                            currentlyPlayingArtifact = currentlyPlayingArtifact,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            currentPosition = currentPosition,
                            duration = duration,
                            isSelf = isSelf,
                            onPlayClick = { viewModel.playAudio(it) },
                            onRename = { _, _ -> },
                            onDelete = { _ -> },
                            onViewComments = { artifact -> 
                                onNavigateToComments(artifact.id, artifact.userId)
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(100.dp)) }
            }

            // Persistent Player
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                currentlyPlayingArtifact?.let { artifact ->
                    BottomPlayer(
                        title = artifact.title.ifEmpty { "Reflective Moment" },
                        isPlaying = isPlaying,
                        progress = if (duration > 0) {
                            currentPosition.toFloat() / duration.toFloat()
                        } else 0f,
                        onTogglePlayback = { viewModel.togglePlayback() },
                        onClick = { }
                    )
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
