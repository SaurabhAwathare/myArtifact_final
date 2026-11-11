package com.saurabh.artifact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.Emotion

import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.Obsidian700
import com.saurabh.artifact.ui.theme.Obsidian800

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
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = "Emotional Resonance",
                style = ArtifactTheme.typography.titleSmall,
                color = ArtifactTheme.colors.onSurfaceMain
            )
            Text(
                text = "${selectedEmotions.size}/$maxSelections selected",
                style = ArtifactTheme.typography.labelSmall,
                color = ArtifactTheme.colors.onSurfaceMuted
            )
        }
        
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            style = ArtifactTheme.typography.bodyMedium
                        )
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Obsidian800,
                        labelColor = ArtifactTheme.colors.onSurfaceMuted,
                        selectedContainerColor = ArtifactTheme.colors.goldAura.copy(alpha = 0.2f),
                        selectedLabelColor = ArtifactTheme.colors.goldAura,
                        disabledContainerColor = Obsidian800.copy(alpha = 0.5f),
                        disabledLabelColor = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.38f)
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = canSelect,
                        selected = isSelected,
                        borderColor = Obsidian700,
                        selectedBorderColor = ArtifactTheme.colors.goldAura,
                        disabledBorderColor = Obsidian700.copy(alpha = 0.5f)
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
