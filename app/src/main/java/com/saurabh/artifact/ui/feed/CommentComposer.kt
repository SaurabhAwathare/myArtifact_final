package com.saurabh.artifact.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.AuthorType
import com.saurabh.artifact.model.VisibilityLayer
import com.saurabh.artifact.ui.components.HiddenCommentNotice
import com.saurabh.artifact.ui.components.MindfulTextField
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.Obsidian800
import com.saurabh.artifact.ui.theme.ReflectionWhite

@Composable
fun CommentComposer(
    artifactId: String,
    viewModel: CommentViewModel,
    onClose: () -> Unit // Kept for compatibility, but hidden in inline mode
) {
    val uiState by viewModel.uiState.collectAsState()
    var content by remember { mutableStateOf("") }
    var visibilityLayer by remember { mutableStateOf(VisibilityLayer.RESONANCE) }
    var authorType by remember { mutableStateOf(AuthorType.PSEUDONYM) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Hidden Comment Notice (Quietly tucked away)
        HiddenCommentNotice()

        Spacer(Modifier.height(16.dp))

        // Text Input
        MindfulTextField(
            value = content,
            onValueChange = { content = it },
            placeholder = "Write a comment...",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Send
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSend = {
                    if (content.isNotBlank() && !uiState.isSubmitting) {
                        viewModel.submitReflection(artifactId, content, visibilityLayer, authorType)
                    }
                }
            )
        )

        Spacer(Modifier.height(16.dp))

        // Visibility Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Visibility:", color = ReflectionWhite.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = visibilityLayer == VisibilityLayer.RESONANCE,
                onClick = { visibilityLayer = VisibilityLayer.RESONANCE },
                label = { Text("Public", fontSize = 10.sp) },
                modifier = Modifier.height(28.dp)
            )
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = visibilityLayer == VisibilityLayer.SANCTUARY,
                onClick = { visibilityLayer = VisibilityLayer.SANCTUARY },
                label = { Text("Private", fontSize = 10.sp) },
                modifier = Modifier.height(28.dp)
            )
        }
        
        Spacer(Modifier.height(8.dp))

        // Options Row (Anonymity)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = authorType == AuthorType.QUIET_PRESENCE, 
                    onCheckedChange = { 
                        authorType = if (it) AuthorType.QUIET_PRESENCE else AuthorType.PSEUDONYM 
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Anonymous", color = ReflectionWhite.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.weight(1f))

            // Action Button
            Button(
                onClick = { 
                    viewModel.submitReflection(artifactId, content, visibilityLayer, authorType)
                },
                enabled = content.isNotBlank() && !uiState.isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = GoldAura400),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Obsidian800, strokeWidth = 2.dp)
                } else {
                    Text("Send", color = Obsidian800, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        AnimatedVisibility(visible = uiState.submissionSuccess) {
            Text(
                "Comment sent.",
                color = GoldAura400,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2000)
                content = ""
            }
        }

        uiState.errorMessage?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
