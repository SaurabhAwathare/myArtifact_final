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
import com.saurabh.artifact.model.CommentVisibilityMode
import com.saurabh.artifact.ui.components.HiddenCommentNotice
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
    var visibilityMode by remember { mutableStateOf(CommentVisibilityMode.HIDDEN) }
    var isAnonymous by remember { mutableStateOf(false) }

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
                        viewModel.submitReflection(artifactId, content, visibilityMode, isAnonymous)
                    }
                }
            )
        )

        Spacer(Modifier.height(24.dp))

        // Visibility Selector
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Visible to:", color = ReflectionWhite, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = visibilityMode == CommentVisibilityMode.HIDDEN,
                onClick = { visibilityMode = CommentVisibilityMode.HIDDEN },
                label = { Text("Private") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = visibilityMode == CommentVisibilityMode.PUBLIC,
                onClick = { visibilityMode = CommentVisibilityMode.PUBLIC },
                label = { Text("Public") }
            )
        }

        Spacer(Modifier.height(16.dp))

        // Anonymous Toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isAnonymous, onCheckedChange = { isAnonymous = it })
            Text("Share Anonymously", color = ReflectionWhite)
        }

        Spacer(Modifier.height(24.dp))

        // Action Buttons
        Button(
            onClick = { viewModel.submitReflection(artifactId, content, visibilityMode, isAnonymous) },
            enabled = content.isNotBlank() && !uiState.isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = GoldAura400)
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Obsidian800)
            } else {
                Text("Send Reflection", color = Obsidian800)
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
