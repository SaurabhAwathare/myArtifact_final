package com.saurabh.artifact.ui.identity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val avatarConfig by viewModel.avatarConfig.collectAsStateWithLifecycle()
    val usernameUiState by viewModel.usernameUiState.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val isUsernameValid by viewModel.isUsernameValid.collectAsStateWithLifecycle()
    val identityMetadata by viewModel.identityMetadata.collectAsStateWithLifecycle()
    val changeSeverity by viewModel.changeSeverity.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showProtectDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState is IdentityUiState.Error) {
            snackbarHostState.showSnackbar((uiState as IdentityUiState.Error).message.asString(context))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Edit Identity", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showProtectDialog = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Protect")
                        }
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
            
            // Large Identity Preview
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clickable { onEditAvatar() },
                    contentAlignment = Alignment.Center
                ) {
                    ArtifactAvatar(
                        config = avatarConfig,
                        size = 140.dp
                    )
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
                text = "Edit Your Anonymous Identity",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Choose an avatar and anonymous name while keeping your real identity private.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            UsernameInput(
                state = usernameUiState,
                onUsernameChange = { viewModel.onUsernameChange(it) },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is IdentityUiState.Loading
            )

            if (suggestions.isNotEmpty()) {
                UsernameSuggestions(
                    suggestions = suggestions,
                    onSuggestionSelected = { viewModel.selectSuggestion(it) },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Card(
                modifier = Modifier.padding(top = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Identity Protection",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        text = "Your anonymous identity helps others recognize you in the community. You can change it anytime if needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    
                    identityMetadata.lastIdentityChangeAt?.let { timestamp ->
                        Spacer(Modifier.height(8.dp))
                        val date = remember(timestamp) {
                            java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale.getDefault())
                                .format(timestamp.toDate())
                        }
                        Text(
                            text = "Last Updated: $date",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (changeSeverity != com.saurabh.artifact.domain.IdentityProtectionPolicy.ChangeSeverity.NORMAL) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (changeSeverity == com.saurabh.artifact.domain.IdentityProtectionPolicy.ChangeSeverity.WARNING)
                                "Frequent changes may affect your recognition."
                            else "Frequent identity changes detected. Consistency builds trust.",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (changeSeverity == com.saurabh.artifact.domain.IdentityProtectionPolicy.ChangeSeverity.WARNING)
                                MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
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
                    .navigationBarsPadding()
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
                    Text("Save Changes", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showProtectDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showProtectDialog = false },
            icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
            title = { Text("Protect My Identity") },
            text = {
                Text("This will immediately randomize your name and avatar to protect your anonymity. This action is recommended if you believe your identity has been exposed.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showProtectDialog = false
                        viewModel.emergencyReset { onComplete() }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Randomize Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProtectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
