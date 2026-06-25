package com.saurabh.artifact.ui.player.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.TranscriptSegment
import com.saurabh.artifact.ui.theme.GoldAura400

/**
 * TranscriptOverlay - A cinematic, synchronized transcript experience.
 * Designed to keep focus on the speaker's emotional pacing.
 */
@Composable
fun TranscriptOverlay(
    segments: List<TranscriptSegment>,
    currentPosition: Long,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(currentPosition, segments) {
        segments.indexOfFirst { currentPosition in it.startMs..it.endMs }
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex != -1) {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = -200 // Keep the active text centered/elevated
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 120.dp), // Spacious top/bottom for focus
        verticalArrangement = Arrangement.spacedBy(24.dp),
        userScrollEnabled = false // Calm, autonomous movement
    ) {
        itemsIndexed(
            items = segments,
            key = { _, segment -> segment.id }
        ) { index, segment ->
            val isActive = index == currentIndex
            val color by animateColorAsState(
                targetValue = if (isActive) GoldAura400 else Color.White.copy(alpha = 0.2f),
                animationSpec = tween(500),
                label = "TranscriptHighlight"
            )

            Text(
                text = segment.text,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 24.sp,
                    lineHeight = 32.sp,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                ),
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
        }
    }
}
