package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.Emotion

/**
 * Predefined list of emotions for consistent user experience
 */
val EmotionList = listOf(
    Emotion.HAPPY,
    Emotion.SAD,
    Emotion.LONELY,
    Emotion.ANXIOUS,
    Emotion.ANGRY,
    Emotion.MOTIVATED,
    Emotion.MIXED,
    Emotion.NEUTRAL
)

/**
 * A modern, chip-based selection UI for emotional tagging.
 * Uses FlowRow to adapt to various screen widths.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmotionSelector(
    selectedEmotion: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "How are you feeling?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EmotionList.forEach { emotion ->
                val isSelected = selectedEmotion.equals(emotion.label, ignoreCase = true)
                
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(emotion.label) },
                    label = {
                        Text(
                            text = "${emotion.emoji} ${emotion.label}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        selectedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}
