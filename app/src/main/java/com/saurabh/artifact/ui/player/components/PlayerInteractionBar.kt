package com.saurabh.artifact.ui.player.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.EngagementStatus
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.InteractionSyncStatus
import com.saurabh.artifact.ui.theme.GoldAura400

/**
 * PlayerInteractionBar - A cinematic, emotionally intentional row of actions.
 * Updated with synchronization awareness and improved touch targets.
 */
@Composable
fun PlayerInteractionBar(
    isResonated: Boolean,
    resonanceSyncStatus: InteractionSyncStatus,
    selectedReactionType: ReactionType,
    onResonateClick: (ReactionType) -> Unit,
    isResonating: Boolean,
    followSyncStatus: InteractionSyncStatus,
    onResonateConnectionClick: () -> Unit,
    isSaved: Boolean,
    saveSyncStatus: InteractionSyncStatus,
    onSaveClick: () -> Unit,
    engagementStatus: EngagementStatus,
    commentCount: Long,
    onCommentClick: () -> Unit,
    modifier: Modifier = Modifier,
    showResonance: Boolean = true,
    showSave: Boolean = true,
) {
    var showReactionPicker by remember { mutableStateOf(value = false) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(24.dp))
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            InteractionItem(
                icon = if (isResonated) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isResonated) selectedReactionType.label else "Resonate",
                count = null,
                isActive = isResonated,
                syncStatus = resonanceSyncStatus,
                activeColor = GoldAura400,
                onClick = { 
                    showReactionPicker = !showReactionPicker
                }
            )

            InteractionItem(
                icon = Icons.Rounded.ChatBubbleOutline,
                label = "Comments",
                count = commentCount,
                isActive = engagementStatus == EngagementStatus.UNLOCKED,
                enabled = engagementStatus == EngagementStatus.UNLOCKED || engagementStatus == EngagementStatus.VERIFYING,
                loading = engagementStatus == EngagementStatus.VERIFYING,
                onClick = onCommentClick
            )

            if (showResonance) {
                InteractionItem(
                    icon = if (isResonating) Icons.Rounded.Person else Icons.Rounded.PersonAdd,
                    label = if (isResonating) "Following" else "Follow",
                    isActive = isResonating,
                    syncStatus = followSyncStatus,
                    activeColor = GoldAura400,
                    onClick = onResonateConnectionClick
                )
            }

            if (showSave) {
                InteractionItem(
                    icon = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    label = if (isSaved) "Held" else "Hold",
                    isActive = isSaved,
                    syncStatus = saveSyncStatus,
                    activeColor = GoldAura400,
                    onClick = onSaveClick
                )
            }
        }

        if (showReactionPicker) {
            ReactionPicker {
                onResonateClick(it)
                showReactionPicker = false
            }
        }
    }
}

@Composable
private fun ReactionPicker(
    onReactionSelected: (ReactionType) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(ReactionType.entries.toList()) { type ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onReactionSelected(type) }
                ) {
                    Text(text = type.emoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = type.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractionItem(
    icon: ImageVector,
    label: String,
    count: Long? = null,
    isActive: Boolean,
    onClick: () -> Unit,
    syncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    enabled: Boolean = true,
    loading: Boolean = false,
    activeColor: Color = Color.White
) {
    val haptic = LocalHapticFeedback.current
    
    val contentColor = if (!enabled && !loading) {
        Color.White.copy(alpha = 0.12f)
    } else if (isActive) {
        if (syncStatus == InteractionSyncStatus.PENDING) activeColor.copy(alpha = 0.5f) else activeColor
    } else {
        if (syncStatus == InteractionSyncStatus.PENDING) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.45f)
    }

    val scale by animateFloatAsState(
        targetValue = if (isActive && syncStatus == InteractionSyncStatus.SYNCED) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ScaleAnimation"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier
                    .size(26.dp)
                    .scale(scale)
                    .alpha(if (loading) 0.3f else 1.0f)
            )
            
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        // Show count if > 0, otherwise show label
        val displayText = if ((count != null) && (count > 0)) {
            count.toString()
        } else {
            label
        }

        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.2.sp
        )
    }
}
