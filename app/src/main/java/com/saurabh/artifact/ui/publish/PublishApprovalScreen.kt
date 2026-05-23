package com.saurabh.artifact.ui.publish

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Obsidian950
import com.saurabh.artifact.ui.theme.Obsidian900

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishApprovalScreen(
    onNavigateBack: () -> Unit,
    onPublishSuccess: () -> Unit,
    viewModel: PublishFlowViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            onPublishSuccess()
        }
    }

    Scaffold(
        containerColor = Obsidian950,
        topBar = {
            TopAppBar(
                title = { Text("Publish Artifact", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // 1. Reflection Confirmation
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Are you comfortable sharing this publicly?",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Once published, others may emotionally connect with your story.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 2. Metadata: Title
            item {
                Column {
                    Text("Give your artifact a title", style = MaterialTheme.typography.labelMedium, color = GoldAura500)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.title,
                        onValueChange = { viewModel.onTitleChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldAura500,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            errorBorderColor = MaterialTheme.colorScheme.error
                        ),
                        placeholder = { Text("Title...", color = Color.White.copy(alpha = 0.3f)) },
                        shape = RoundedCornerShape(12.dp),
                        isError = uiState.title.isBlank(),
                        supportingText = {
                            if (uiState.title.isBlank()) {
                                Text("Add a title to continue")
                            }
                        }
                    )
                }
            }

            // 3. Metadata: Description
            item {
                Column {
                    Text("Description (Optional)", style = MaterialTheme.typography.labelMedium, color = GoldAura500)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = { viewModel.onDescriptionChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldAura500,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        placeholder = { Text("What is this artifact about?", color = Color.White.copy(alpha = 0.3f)) },
                        shape = RoundedCornerShape(12.dp),
                        minLines = 3
                    )
                }
            }

            // 3. Emotion Tags
            item {
                Column {
                    Text("Emotion Tags (Optional)", style = MaterialTheme.typography.labelMedium, color = GoldAura500)
                    Spacer(modifier = Modifier.height(12.dp))
                    // Simplification for now: Row of chips
                    val emotions = listOf("lonely", "hopeful", "exhausted", "grateful")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        emotions.forEach { emotion ->
                            FilterChip(
                                selected = uiState.emotion == emotion,
                                onClick = { viewModel.onEmotionChange(emotion) },
                                label = { Text(emotion) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = GoldAura500,
                                    selectedLabelColor = Obsidian950,
                                    labelColor = Color.White.copy(alpha = 0.5f)
                                ),
                                border = null,
                                shape = RoundedCornerShape(20.dp)
                            )
                        }
                    }
                }
            }

            // 4. Topic Tags
            item {
                Column {
                    Text("Topic Tags (Optional)", style = MaterialTheme.typography.labelMedium, color = GoldAura500)
                    Spacer(modifier = Modifier.height(12.dp))
                    val topics = listOf("relationships", "filmmaking", "anxiety", "family")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        topics.forEach { topic ->
                            FilterChip(
                                selected = false,
                                onClick = { },
                                label = { Text(topic) },
                                colors = FilterChipDefaults.filterChipColors(
                                    labelColor = Color.White.copy(alpha = 0.5f)
                                ),
                                border = null,
                                shape = RoundedCornerShape(20.dp)
                            )
                        }
                    }
                }
            }

            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!uiState.isListened) {
                        Text(
                            text = "Please listen to the entire artifact before publishing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    
                    Button(
                        onClick = { viewModel.onApproveAndPublish() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoldAura500, contentColor = Obsidian950),
                        shape = RoundedCornerShape(28.dp),
                        enabled = uiState.canApprove
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Obsidian950)
                        } else {
                            Text("Publish Artifact", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    horizontalArrangement: Arrangement.Horizontal,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = horizontalArrangement,
        content = { content() }
    )
}
