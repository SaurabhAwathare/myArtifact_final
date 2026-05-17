package com.saurabh.artifact.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.theme.Obsidian800
import com.saurabh.artifact.ui.theme.MistGray

/**
 * PRODUCTION-GRADE REACTION BAR
 * A subtle, horizontal row of emotional resonance options.
 */
@Composable
fun ReactionBar(
    selectedType: ReactionType?,
    onReactionSelect: (ReactionType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "How does this make you feel?",
            style = MaterialTheme.typography.labelMedium,
            color = MistGray.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(ReactionType.entries) { type ->
                ReactionChip(
                    type = type,
                    isSelected = type == selectedType,
                    onClick = { onReactionSelect(type) }
                )
            }
        }
    }
}

@Composable
fun ReactionChip(
    type: ReactionType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (isSelected) 1.05f else 1f, label = "scale")
    val backgroundColor by animateColorAsState(
        if (isSelected) GoldAura400.copy(alpha = 0.15f) else Obsidian800,
        label = "bgColor"
    )
    val contentColor by animateColorAsState(
        if (isSelected) GoldAura400 else MistGray,
        label = "contentColor"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, GoldAura400.copy(alpha = 0.3f)) else null,
        modifier = Modifier
            .scale(scale)
            .height(40.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(text = type.emoji, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            Text(
                text = type.label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor
            )
        }
    }
}
