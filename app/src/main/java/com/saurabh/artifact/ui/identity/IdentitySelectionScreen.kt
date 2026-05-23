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
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

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
                        modifier = Modifier.padding(8.dp).size(20.dp).clickable { viewModel.randomizePresence() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "Shape Your Anonymous Identity",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Create a quiet presence that reflects your inner self while protecting your anonymity.",
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
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
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
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "You can change your name again in $cooldownDays days",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
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
                    Text("Seal Identity", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
