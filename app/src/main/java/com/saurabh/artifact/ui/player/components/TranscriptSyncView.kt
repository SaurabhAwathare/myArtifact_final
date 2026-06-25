package com.saurabh.artifact.ui.player.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.saurabh.artifact.ui.theme.EmberGlow

@Composable
fun TranscriptSyncView(
    segments: List<TranscriptSegment>,
    currentPosition: Long,
    onSegmentClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll to current segment
    val currentSegmentIndex = segments.indexOfFirst { currentPosition in it.startMs..it.endMs }
    
    LaunchedEffect(currentSegmentIndex) {
        if (currentSegmentIndex != -1) {
            listState.animateScrollToItem(currentSegmentIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = segments,
            key = { segment -> segment.id }
        ) { segment ->
            val isActive = currentPosition in segment.startMs..segment.endMs
            val color by animateColorAsState(
                targetValue = if (isActive) EmberGlow else Color.White.copy(alpha = 0.4f),
                label = "TranscriptColor"
            )

            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSegmentClick(segment.startMs) }
                    .padding(horizontal = 8.dp)
            )
        }
    }
}
