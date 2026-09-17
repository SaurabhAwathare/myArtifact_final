package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.Emotion

/**
 * Canonical list of emotions derived directly from Emotion enum as the single source of truth.
 */
val EmotionList = Emotion.entries.toList()

/**
 * A modern, chip-based selection UI for emotional tagging supporting up to [maxSelections] emotions.
 * Uses FlowRow to adapt to various screen widths.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmotionSelector(
    selectedEmotions: List<String>,
    onEmotionToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxSelections: Int = 3
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "How are you feeling?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${selectedEmotions.size}/$maxSelections",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EmotionList.forEach { emotion ->
                val isSelected = selectedEmotions.any { it.equals(emotion.label, ignoreCase = true) || it.equals(emotion.name, ignoreCase = true) }
                val canSelect = isSelected || selectedEmotions.size < maxSelections
                
                FilterChip(
                    selected = isSelected,
                    enabled = canSelect,
                    onClick = {
                        if (isSelected || selectedEmotions.size < maxSelections) {
                            onEmotionToggle(emotion.label)
                        }
                    },
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
                        enabled = canSelect,
                        selected = isSelected,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        selectedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

/**
 * Legacy single-selection compatibility overload.
 */
@Composable
fun EmotionSelector(
    selectedEmotion: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    EmotionSelector(
        selectedEmotions = if (selectedEmotion.isNotBlank()) listOf(selectedEmotion) else emptyList(),
        onEmotionToggle = { label -> onSelect(label) },
        modifier = modifier,
        maxSelections = 1
    )
}
