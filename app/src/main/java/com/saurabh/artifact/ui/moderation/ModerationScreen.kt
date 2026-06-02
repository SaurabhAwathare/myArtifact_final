package com.saurabh.artifact.ui.moderation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.repository.ArtifactRepository
import com.saurabh.artifact.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationScreen(
    onBack: () -> Unit,
    viewModel: ModerationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moderation Dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is ModerationUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ModerationUiState.Empty -> {
                    Text(
                        text = "No pending reports.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is ModerationUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is ModerationUiState.Success -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
                    ) {
                        items(state.items) { item ->
                            ReportCard(
                                item = item,
                                onAction = { action ->
                                    viewModel.resolveReport(item.report.id, item.report.artifactId, action, item.report.commentId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportCard(
    item: ReportItem,
    onAction: (ArtifactRepository.ModerationAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(Spacing.Medium)) {
            Text(
                text = "Reason: ${item.report.reason.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (item.report.details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.Small))
                Text(
                    text = "Details: ${item.report.details}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(Spacing.Medium))
            Divider()
            Spacer(modifier = Modifier.height(Spacing.Medium))

            Text(
                text = "Artifact Content:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            item.artifact?.let { artifact ->
                Text(
                    text = "Artifact Title: ${artifact.title}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Artifact Author: ${artifact.author.name}",
                    style = MaterialTheme.typography.bodySmall
                )
            } ?: Text("Artifact details unavailable", color = MaterialTheme.colorScheme.error)

            item.comment?.let { comment ->
                Spacer(modifier = Modifier.height(Spacing.Medium))
                Text(
                    text = "Reported Comment:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Comment Author: ${comment.authorAnonymousName ?: "Quiet Presence"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(Spacing.Large))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { onAction(ArtifactRepository.ModerationAction.DISMISS) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(Spacing.Small))
                    Text("Dismiss")
                }
                
                Spacer(modifier = Modifier.width(Spacing.Small))

                if (item.report.commentId != null) {
                    Button(
                        onClick = { onAction(ArtifactRepository.ModerationAction.BLOCK_COMMENT) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(Spacing.Small))
                        Text("Block Comment")
                    }
                } else {
                    Button(
                        onClick = { onAction(ArtifactRepository.ModerationAction.HIDE_ARTIFACT) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(Spacing.Small))
                        Text("Hide Artifact")
                    }
                }
            }
        }
    }
}
