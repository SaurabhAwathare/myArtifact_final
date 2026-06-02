package com.saurabh.artifact.ui.publish

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.Emotion
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Spacing

/**
 * The final stage before publishing.
 * Where the user intentionally adds title, emotions, and metadata.
 */
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saurabh.artifact.ui.components.moderation.PrivacyNudgeDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishPreparationScreen(
    draftId: String,
    onPublished: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PublishViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(draftId) {
        viewModel.loadDraft(draftId)
    }

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess && !uiState.isQueuedOffline) {
            onPublished()
        }
    }

    if (uiState.isSuccess && uiState.isQueuedOffline) {
        AlertDialog(
            onDismissRequest = { onPublished() },
            title = { Text("Queued for Upload") },
            text = { 
                Text("You're currently offline. Your artifact has been saved and will be published automatically as soon as you're back online.") 
            },
            confirmButton = {
                TextButton(onClick = { onPublished() }) {
                    Text("Got it")
                }
            },
            containerColor = ArtifactTheme.colors.surfaceHearth,
            titleContentColor = ArtifactTheme.colors.onSurfaceMain,
            textContentColor = ArtifactTheme.colors.onSurfaceMuted
        )
    }

    if (uiState.showPrivacyNudge) {
        PrivacyNudgeDialog(
            onDismiss = { viewModel.dismissPrivacyNudge() },
            onConfirm = { viewModel.confirmPublishAnyway() },
            leaks = uiState.privacyWarnings
        )
    }

    Scaffold(
        containerColor = ArtifactTheme.colors.surfaceLoom,
        topBar = {
            TopAppBar(
                title = { Text("Finalize Artifact", style = ArtifactTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = ArtifactTheme.colors.onSurfaceMain,
                    navigationIconContentColor = ArtifactTheme.colors.onSurfaceMuted
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.Large)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Give your reflection a voice.",
                style = ArtifactTheme.typography.headlineSmall,
                color = ArtifactTheme.colors.onSurfaceMain,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(Spacing.Large))

            OutlinedTextField(
                value = uiState.title,
                onValueChange = { viewModel.updateTitle(it) },
                label = { Text("Title") },
                placeholder = { Text("How should we remember this?") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Spacing.Medium),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ArtifactTheme.colors.waveformActive,
                    unfocusedBorderColor = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.3f)
                )
            )

            Spacer(modifier = Modifier.height(Spacing.ExtraLarge))

            Text(
                text = "How does this artifact feel?",
                style = ArtifactTheme.typography.titleMedium,
                color = ArtifactTheme.colors.onSurfaceMain,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(Spacing.Medium))

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small)
            ) {
                Emotion.entries.forEach { emotion ->
                    val isSelected = uiState.emotion == emotion
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.updateEmotion(emotion) },
                        label = { 
                            Text("${emotion.emoji} ${emotion.label}") 
                        },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ArtifactTheme.colors.waveformActive,
                            selectedLabelColor = Color.White,
                            containerColor = ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.5f),
                            labelColor = ArtifactTheme.colors.onSurfaceMain
                        ),
                        border = if (!isSelected) FilterChipDefaults.filterChipBorder(
                            borderColor = Color.Transparent,
                            borderWidth = 0.dp,
                            enabled = true,
                            selected = false
                        ) else null
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(Spacing.ExtraLarge))

            Button(
                onClick = { viewModel.onPublishClick() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(Spacing.ExtraLarge),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ArtifactTheme.colors.waveformActive,
                    contentColor = Color.White
                ),
                enabled = !uiState.isPublishing && uiState.title.isNotBlank() && uiState.emotion != null
            ) {
                if (uiState.isPublishing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Rounded.Check, null)
                    Spacer(modifier = Modifier.width(Spacing.Small))
                    Text("Publish to the World", fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(Spacing.Medium))
            
            Text(
                text = "This will make your artifact public to your chosen audience.",
                style = ArtifactTheme.typography.labelSmall,
                color = ArtifactTheme.colors.onSurfaceMuted
            )
        }
    }
}
