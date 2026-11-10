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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.InteractionSyncStatus
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.util.FeedbackUtils

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
    resonanceCount: Int,
    canShowResonators: Boolean,
    onResonatorsCountClick: () -> Unit,
    isResonating: Boolean,
    followSyncStatus: InteractionSyncStatus,
    onResonateConnectionClick: () -> Unit,
    isSaved: Boolean,
    saveSyncStatus: InteractionSyncStatus,
    onSaveClick: () -> Unit,
    onCommentClick: () -> Unit,
    modifier: Modifier = Modifier,
    isOwner: Boolean = false,
    showResonance: Boolean = true,
    showSave: Boolean = true,
    isCommentEnabled: Boolean = true,
    commentDisabledReason: String? = null,
) {
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
            ResonateInteractionItem(
                isResonated = isResonated,
                resonanceCount = resonanceCount,
                canShowResonators = canShowResonators,
                syncStatus = resonanceSyncStatus,
                isOwner = isOwner,
                onResonateClick = { onResonateClick(ReactionType.I_HEAR_YOU) },
                onCountClick = onResonatorsCountClick
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

            InteractionItem(
                icon = Icons.Rounded.ChatBubbleOutline,
                label = "Comment",
                isActive = false,
                enabled = isCommentEnabled,
                disabledReason = commentDisabledReason,
                activeColor = GoldAura400,
                onClick = onCommentClick
            )
        }
    }
}

@Composable
private fun ResonateInteractionItem(
    isResonated: Boolean,
    resonanceCount: Int,
    canShowResonators: Boolean,
    onResonateClick: () -> Unit,
    onCountClick: () -> Unit,
    syncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    isOwner: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val showCount = canShowResonators && resonanceCount > 0
    val enabled = !isOwner
    val disabledReason = if (isOwner) "You cannot resonate with your own reflection." else null
    
    val activeColor = GoldAura400
    val contentColor = if (!enabled) {
        Color.White.copy(alpha = 0.2f)
    } else if (isResonated) {
        if (syncStatus == InteractionSyncStatus.PENDING) activeColor.copy(alpha = 0.5f) else activeColor
    } else {
        if (syncStatus == InteractionSyncStatus.PENDING) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.45f)
    }

    val scale by animateFloatAsState(
        targetValue = if (isResonated && syncStatus == InteractionSyncStatus.SYNCED) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ScaleAnimation"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (isResonated && showCount) activeColor.copy(alpha = 0.08f) else Color.Transparent
            )
    ) {
        // 1. Heart Toggle Target (At least 48dp x 48dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(
                    onClickLabel = "Resonate"
                ) {
                    if (enabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onResonateClick()
                    } else if (disabledReason != null) {
                        FeedbackUtils.explainDisabledAction(
                            context,
                            haptic,
                            disabledReason
                        )
                    }
                }
        ) {
            Icon(
                imageVector = if (isResonated) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = "Resonate",
                tint = contentColor,
                modifier = Modifier
                    .size(26.dp)
                    .scale(scale)
            )
        }

        // 2. Resonators Count Target (At least 48dp x 48dp, non-overlapping)
        if (showCount) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        onClickLabel = "View resonators"
                    ) {
                        onCountClick()
                    }
                    .padding(horizontal = 6.dp)
            ) {
                Text(
                    text = "$resonanceCount \u203A",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    fontSize = 11.sp,
                    fontWeight = if (isResonated) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 0.2.sp
                )
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
    disabledReason: String? = null,
    loading: Boolean = false,
    activeColor: Color = Color.White
) {
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    
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
            .clickable(enabled = enabled || disabledReason != null) {
                if (enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                } else if (disabledReason != null) {
                    com.saurabh.artifact.ui.util.FeedbackUtils.explainDisabledAction(
                        context,
                        haptic,
                        disabledReason
                    )
                }
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
