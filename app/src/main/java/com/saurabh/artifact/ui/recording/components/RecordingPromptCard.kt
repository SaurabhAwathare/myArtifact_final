package com.saurabh.artifact.ui.recording.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.ReflectionPrompt

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF050101)
@Composable
fun RecordingPromptCardPreview() {
    val mockPrompt = ReflectionPrompt(
        id = "1",
        category = com.saurabh.artifact.model.PromptCategory.SELF_REFLECTION,
        question = "What emotion have you been carrying silently lately?",
        tone = com.saurabh.artifact.model.EmotionalTone.REFLECTIVE
    )
    RecordingPromptCard(
        prompt = mockPrompt,
        onNext = {},
        onPrevious = {},
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
fun RecordingPromptCard(
    prompt: ReflectionPrompt,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0x11FFB84D)
        ),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(
            1.dp,
            Color(0x22FFB84D)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 24.dp,
                    vertical = 28.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Reflection Prompt",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFFFB84D)
            )

            Spacer(modifier = Modifier.height(18.dp))

            AnimatedContent(
                targetState = prompt.question,
                label = "prompt_animation",
                transitionSpec = {
                    fadeIn() + slideInVertically { it } togetherWith
                    fadeOut() + slideOutVertically { -it }
                }
            ) { question ->
                Text(
                    text = question,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    lineHeight = 32.sp,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(prompt.category.displayName)
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        labelColor = Color.White.copy(alpha = 0.7f)
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = Color.White.copy(alpha = 0.2f)
                    )
                )

                SuggestionChip(
                    onClick = {},
                    label = {
                        Text("Guided")
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        labelColor = Color.White.copy(alpha = 0.7f)
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = Color.White.copy(alpha = 0.2f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onPrevious,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.White.copy(alpha = 0.6f)
                    )
                ) {
                    Text("Previous")
                }

                FilledTonalButton(
                    onClick = onNext,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text("Next")
                }
            }
        }
    }
}
