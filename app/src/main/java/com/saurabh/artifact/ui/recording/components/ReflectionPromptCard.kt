package com.saurabh.artifact.ui.recording.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
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

@Composable
fun ReflectionPromptCard(
    prompt: ReflectionPrompt?,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = prompt,
            transitionSpec = {
                fadeIn() + slideInHorizontally { it } togetherWith
                fadeOut() + slideOutHorizontally { -it }
            },
            label = "PromptTransition"
        ) { currentPrompt ->
            if (currentPrompt != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Category Label
                    Text(
                        text = currentPrompt.category.displayName.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Light
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Large Prompt Text
                    Text(
                        text = currentPrompt.question,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(48.dp))

                    // Next Prompt Button
                    TextButton(
                        onClick = onNext,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.6f))
                    ) {
                        Text("Next Prompt")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
