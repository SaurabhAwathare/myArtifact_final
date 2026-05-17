package com.saurabh.artifact.ui.transcription

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.ConversationStyle
import com.saurabh.artifact.model.StyleModerationState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversationStyleScreen(
    uiState: ConversationStyleUiState,
    onPrimarySelected: (ConversationStyle) -> Unit,
    onSecondaryToggled: (ConversationStyle) -> Unit,
    onBack: () -> Unit,
    onPublish: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conversational Style", color = Color(0xFFF2E7D5)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // Back Icon
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D0D))
            )
        },
        bottomBar = {
            StyleBottomBar(
                canPublish = uiState.selectedPrimaryStyle != null,
                isSaving = uiState.isSaving,
                onPublish = onPublish
            )
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(vertical = 24.dp)
        ) {
            item {
                Text(
                    text = "How would you describe this conversation?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFF2E7D5)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This helps us find the right moment for someone to listen to your story.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFBDBDBD)
                )
            }

            if (uiState.aiSuggestions.isNotEmpty()) {
                item {
                    AISuggestionSection(
                        suggestions = uiState.aiSuggestions,
                        onStyleClick = onPrimarySelected
                    )
                }
            }

            item {
                StyleSectionHeader(title = "Primary Style", subtitle = "Choose the main energy of this recording")
                Spacer(modifier = Modifier.height(16.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ConversationStyle.entries.forEach { style ->
                        StyleChip(
                            style = style,
                            isSelected = uiState.selectedPrimaryStyle == style,
                            onClick = { onPrimarySelected(style) }
                        )
                    }
                }
            }

            item {
                StyleSectionHeader(title = "Secondary Styles", subtitle = "Optional nuances (up to 2)")
                Spacer(modifier = Modifier.height(16.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ConversationStyle.entries.forEach { style ->
                        val isDisabled = uiState.selectedPrimaryStyle == style
                        StyleChip(
                            style = style,
                            isSelected = uiState.selectedSecondaryStyles.contains(style),
                            enabled = !isDisabled,
                            onClick = { onSecondaryToggled(style) }
                        )
                    }
                }
            }

            if (uiState.moderationState == StyleModerationState.SENSITIVE) {
                item {
                    ModerationWarning()
                }
            }

            item {
                ListenerPreviewCard(count = uiState.previewListenerCount)
            }
        }
    }
}

@Composable
fun StyleChip(
    style: ConversationStyle,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val chipAlpha by animateFloatAsState(if (enabled) 1f else 0.3f, label = "chipAlpha")
    
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (isSelected) Color(0xFFFFB300) else Color(0xFF1A1A1A))
            .border(
                width = 1.dp,
                color = if (isSelected) Color(0xFFFFB300) else Color(0xFF333333),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .graphicsLayer(alpha = chipAlpha)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = style.iconEmoji, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = style.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Color.Black else Color(0xFFF2E7D5)
            )
        }
    }
}

@Composable
fun AISuggestionSection(
    suggestions: List<com.saurabh.artifact.model.StyleSuggestion>,
    onStyleClick: (ConversationStyle) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFFB300).copy(alpha = 0.1f))
            .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "AI Suggestion",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFFFB300)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "This sounds like ${suggestions.firstOrNull()?.style?.label ?: "a conversation"} to us.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFF2E7D5)
        )
    }
}

@Composable
fun ListenerPreviewCard(count: Int) {
    Surface(
        color = Color(0xFF1A1A1A),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFB300))
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    "Recommended for $count+ people",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFF2E7D5)
                )
                Text(
                    "Based on current listening styles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFBDBDBD)
                )
            }
        }
    }
}

@Composable
fun ModerationWarning() {
    Surface(
        color = Color(0xFFE57373).copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE57373))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "This style combination feels high-energy. We'll distribute it to listeners who are ready for this mood.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE57373)
            )
        }
    }
}

@Composable
fun StyleSectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF2E7D5))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFFBDBDBD))
    }
}

@Composable
fun StyleBottomBar(canPublish: Boolean, isSaving: Boolean, onPublish: () -> Unit) {
    Surface(
        color = Color(0xFF1A1A1A),
        tonalElevation = 8.dp
    ) {
        Button(
            onClick = onPublish,
            enabled = canPublish && !isSaving,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFB300),
                disabledContainerColor = Color(0xFF333333)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .height(56.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
            } else {
                Text("Confirm & Publish", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
