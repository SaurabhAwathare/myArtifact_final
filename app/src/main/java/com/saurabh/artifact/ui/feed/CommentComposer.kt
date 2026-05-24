package com.saurabh.artifact.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.saurabh.artifact.model.AuthorType
import com.saurabh.artifact.model.VisibilityLayer
import com.saurabh.artifact.ui.components.HiddenCommentNotice
import java.util.Date
import com.saurabh.artifact.ui.components.MindfulTextField
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.Obsidian800
import com.saurabh.artifact.ui.theme.ReflectionWhite

@Composable
fun CommentComposer(
    artifactId: String,
    viewModel: CommentViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var content by remember { mutableStateOf("") }
    var visibilityLayer by remember { mutableStateOf(VisibilityLayer.BRIDGE) }
    var authorType by remember { mutableStateOf(AuthorType.PSEUDONYM) }
    var isDelayed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header with Close
        Box(modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = ReflectionWhite)
            }
        }

        // Emotional Expectation Setting
        HiddenCommentNotice()

        Spacer(Modifier.height(16.dp))

        // Text Input
        MindfulTextField(
            value = content,
            onValueChange = { content = it },
            placeholder = "What does this artifact evoke in you?",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Send
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSend = {
                    if (content.isNotBlank() && !uiState.isSubmitting) {
                        val revealAt = if (isDelayed) Timestamp(Date(System.currentTimeMillis() + (24 * 60 * 60 * 1000))) else null
                        viewModel.submitReflection(artifactId, content, visibilityLayer, authorType, revealAt)
                    }
                }
            )
        )

        Spacer(Modifier.height(24.dp))

        // Visibility Selector
        Text("Visibility Layer:", color = ReflectionWhite, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            FilterChip(
                selected = visibilityLayer == VisibilityLayer.SANCTUARY,
                onClick = { visibilityLayer = VisibilityLayer.SANCTUARY },
                label = { Text("Sanctuary") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = visibilityLayer == VisibilityLayer.BRIDGE,
                onClick = { visibilityLayer = VisibilityLayer.BRIDGE },
                label = { Text("Bridge") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = visibilityLayer == VisibilityLayer.RESONANCE,
                onClick = { visibilityLayer = VisibilityLayer.RESONANCE },
                label = { Text("Resonance") }
            )
        }
        
        Text(
            text = when(visibilityLayer) {
                VisibilityLayer.SANCTUARY -> "Private only to you."
                VisibilityLayer.BRIDGE -> "Shared only with the creator."
                VisibilityLayer.RESONANCE -> "Visible to other listeners."
            },
            style = MaterialTheme.typography.bodySmall,
            color = ReflectionWhite.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        // Author Type Toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = authorType == AuthorType.QUIET_PRESENCE, 
                onCheckedChange = { 
                    authorType = if (it) AuthorType.QUIET_PRESENCE else AuthorType.PSEUDONYM 
                }
            )
            Text("Quiet Presence (Fully Anonymous)", color = ReflectionWhite)
        }

        // Delay Toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isDelayed, onCheckedChange = { isDelayed = it })
            Text("Release in 24 hours (Mindful Delay)", color = ReflectionWhite)
        }

        Spacer(Modifier.height(24.dp))

        // Action Buttons
        Button(
            onClick = { 
                val revealAt = if (isDelayed) Timestamp(Date(System.currentTimeMillis() + (24 * 60 * 60 * 1000))) else null
                viewModel.submitReflection(artifactId, content, visibilityLayer, authorType, revealAt) 
            },
            enabled = content.isNotBlank() && !uiState.isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = GoldAura400)
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Obsidian800)
            } else {
                Text("Release into the Hearth", color = Obsidian800)
            }
        }

        AnimatedVisibility(visible = uiState.submissionSuccess) {
            Text(
                "Your reflection has been safely delivered.",
                color = GoldAura400,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
