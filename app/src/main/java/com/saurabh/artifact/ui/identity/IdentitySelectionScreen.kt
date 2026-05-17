package com.saurabh.artifact.ui.identity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.saurabh.artifact.ui.avatar.AvatarRenderer
import com.saurabh.artifact.ui.identity.components.EmojiPicker
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
    val selectedEmoji by viewModel.selectedEmoji.collectAsState()
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
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Choose Your Marker",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Pick an emoji and a name. This helps others recognize your voice without knowing your true identity.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Avatar Preview & Name Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    onClick = onEditAvatar
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (avatarConfig != null) {
                            AvatarRenderer(
                                config = avatarConfig!!,
                                modifier = Modifier.size(60.dp)
                            )
                        } else {
                            Text(
                                text = selectedEmoji,
                                fontSize = 40.sp
                            )
                        }
                    }
                }

                UsernameInput(
                    state = usernameUiState,
                    onUsernameChange = { viewModel.onUsernameChange(it) },
                    modifier = Modifier.weight(1f)
                )
            }

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

            Text(
                text = "Select an emoji marker",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
            )

            Box(modifier = Modifier.height(180.dp)) {
                if (avatarConfig == null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Or create a custom anonymous avatar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onEditAvatar,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Avatar Builder")
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        EmojiPicker(
                            selectedEmoji = selectedEmoji,
                            onEmojiSelected = { viewModel.selectEmoji(it) }
                        )
                    }
                } else {
                    EmojiPicker(
                        selectedEmoji = selectedEmoji,
                        onEmojiSelected = { viewModel.selectEmoji(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

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
                    Text("Confirm Identity", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
