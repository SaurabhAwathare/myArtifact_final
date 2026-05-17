package com.saurabh.artifact.ui.recording.components

import androidx.compose.animation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.ReflectionPrompt

@Composable
fun QuestionBank(
    prompts: List<ReflectionPrompt>,
    selectedPrompt: ReflectionPrompt?,
    onPromptSelected: (ReflectionPrompt) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "CHOOSE A PROMPT",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.5f),
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(prompts) { prompt ->
                QuestionCard(
                    prompt = prompt,
                    isSelected = prompt.id == selectedPrompt?.id,
                    onClick = { onPromptSelected(prompt) }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Display selected prompt in larger text for better readability during recording
        AnimatedContent(
            targetState = selectedPrompt,
            transitionSpec = {
                fadeIn() + slideInVertically { it } togetherWith
                fadeOut() + slideOutVertically { -it }
            },
            label = "SelectedPromptTransition"
        ) { prompt ->
            if (prompt != null) {
                Text(
                    text = prompt.question,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .fillMaxWidth(),
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun QuestionCard(
    prompt: ReflectionPrompt,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f)
    val borderColor = if (isSelected) Color.White.copy(alpha = 0.5f) else Color.Transparent

    Surface(
        modifier = Modifier
            .width(160.dp)
            .height(100.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = backgroundColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier.padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = prompt.question,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = if (isSelected) 1f else 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
        }
    }
}
