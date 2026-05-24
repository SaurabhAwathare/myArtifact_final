package com.saurabh.artifact.ui.identity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.material.icons.rounded.Refresh
import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.ui.identity.components.UsernameInput
import com.saurabh.artifact.ui.identity.components.UsernameSuggestions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitySelectionScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit,
    onEditAvatar: () -> Unit,
    viewModel: IdentityViewModel = hiltViewModel()
) {
    val avatarConfig by viewModel.avatarConfig.collectAsState()
    val usernameUiState by viewModel.usernameUiState.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val isUsernameValid by viewModel.isUsernameValid.collectAsState()
    val cooldownDays by viewModel.cooldownDays.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    var showRefreshConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState is IdentityUiState.Error) {
            snackbarHostState.showSnackbar((uiState as IdentityUiState.Error).message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Large Presence Preview
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .clickable { onEditAvatar() },
                    contentAlignment = Alignment.Center
                ) {
                    ArtifactAvatar(
                        config = avatarConfig,
                        size = 140.dp
                    )
                    
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 2.dp
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh Presence",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp).size(20.dp).clickable { 
                                if (cooldownDays == 0) {
                                    showRefreshConfirmation = true
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Public Identity Preview
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = userProfile?.anonymousName ?: "Searching...",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                    userProfile?.anonymousSigil?.let { sigil ->
                        if (sigil.isNotEmpty()) {
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = sigil,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Light
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "Shape Your Presence",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Create a quiet identity that reflects your inner self while protecting your anonymity in the archive.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            UsernameInput(
                state = usernameUiState,
                onUsernameChange = { viewModel.onUsernameChange(it) },
                modifier = Modifier.fillMaxWidth()
            )

            if (suggestions.isNotEmpty() && cooldownDays == 0) {
                UsernameSuggestions(
                    suggestions = suggestions,
                    onSuggestionSelected = { viewModel.selectSuggestion(it) },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (cooldownDays > 0) {
                Card(
                    modifier = Modifier.padding(top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Your presence is settling. You can emerge again in $cooldownDays days.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { viewModel.saveIdentity(onComplete) },
                enabled = isUsernameValid && uiState !is IdentityUiState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (uiState is IdentityUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Seal Presence", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showRefreshConfirmation) {
        AlertDialog(
            onDismissRequest = { showRefreshConfirmation = false },
            title = { Text("Refresh Presence?") },
            text = { 
                Text("You are about to shed your current presence and emerge with a new randomized identity. This ritual can only be performed once every 30 days.") 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRefreshConfirmation = false
                        viewModel.refreshPresence {
                            // Optionally navigate back or show success
                        }
                    }
                ) {
                    Text("Begin Ritual", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshConfirmation = false }) {
                    Text("Stay as I am")
                }
            }
        )
    }
}
