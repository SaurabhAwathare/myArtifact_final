package com.saurabh.artifact.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.ReflectionPrompt
import com.saurabh.artifact.service.SafetyLevel

@Composable
fun ReflectionPromptCard(
    prompt: ReflectionPrompt?,
    isLoading: Boolean,
    onUse: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    safetyLevel: SafetyLevel = SafetyLevel.LOW
) {
    val containerColor = when (safetyLevel) {
        SafetyLevel.HIGH -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
        SafetyLevel.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
        SafetyLevel.LOW -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    }

    val tintColor = when (safetyLevel) {
        SafetyLevel.HIGH -> MaterialTheme.colorScheme.error
        SafetyLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary
        SafetyLevel.LOW -> MaterialTheme.colorScheme.primary
    }

    AnimatedContent(
        targetState = prompt to isLoading,
        transitionSpec = {
            fadeIn() + expandVertically() togetherWith fadeOut() + shrinkVertically()
        },
        label = "PromptAnimation"
    ) { (currentPrompt, loading) ->
        Surface(
            color = containerColor,
            shape = RoundedCornerShape(24.dp),
            border = AssistChipDefaults.assistChipBorder(enabled = true),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .alpha(if (loading) 0.5f else 1f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = tintColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = currentPrompt?.category?.displayName?.uppercase() ?: "REFLECTING",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = tintColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        
                        IconButton(
                            onClick = onRefresh,
                            enabled = !loading,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Another prompt",
                                tint = tintColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = currentPrompt?.question ?: "Finding the right question for you...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { currentPrompt?.let { onUse(it.question) } },
                        enabled = !loading && currentPrompt != null,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tintColor,
                            contentColor = if (safetyLevel == SafetyLevel.LOW) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary
                        )
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (safetyLevel == SafetyLevel.HIGH) "I hear you" else "Reflect on this",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = tintColor
                    )
                }
            }
        }
    }
}
