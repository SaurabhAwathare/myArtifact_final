package com.saurabh.artifact.ui.player.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.ReactionType
import com.saurabh.artifact.model.InteractionSyncStatus
import com.saurabh.artifact.ui.theme.CountBadgeBackground
import com.saurabh.artifact.ui.theme.CountBadgeText
import com.saurabh.artifact.ui.theme.GoldAura400
import com.saurabh.artifact.ui.util.FeedbackUtils

/**
 * PlayerInteractionBar - A cinematic, emotionally intentional row of 5 standardized actions.
 * Features icon-attached numerical badges, unified single-line labels, guaranteed touch targets (>=48dp),
 * and balanced spacing across the available viewport width (Phase 2.18 / Phase 2.18.2B).
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
    qualifiedPlayCount: Long = 0L,
    showQualifiedPlayCount: Boolean = true,
    commentCount: Long = 0L
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(24.dp))
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Qualified listening count (Listens)
            if (showQualifiedPlayCount) {
                QualifiedPlayCountToolbarItem(count = qualifiedPlayCount)
            }

            // 2. Resonate control & count badge
            ResonateInteractionItem(
                isResonated = isResonated,
                resonanceCount = resonanceCount,
                canShowResonators = canShowResonators,
                onResonateClick = { onResonateClick(ReactionType.I_HEAR_YOU) },
                onCountClick = onResonatorsCountClick,
                syncStatus = resonanceSyncStatus,
                isOwner = isOwner
            )

            // 3. Following (clearly distinguishable HowToReg active glyph vs PersonAdd inactive glyph)
            if (showResonance) {
                InteractionItem(
                    icon = if (isResonating) Icons.Rounded.HowToReg else Icons.Rounded.PersonAdd,
                    label = if (isResonating) "Following" else "Follow",
                    onClick = onResonateConnectionClick,
                    isActive = isResonating,
                    syncStatus = followSyncStatus,
                    activeColor = GoldAura400
                )
            }

            // 4. Save (aligned with SavedArtifactManager persistence)
            if (showSave) {
                InteractionItem(
                    icon = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    label = if (isSaved) "Saved" else "Save",
                    onClick = onSaveClick,
                    isActive = isSaved,
                    syncStatus = saveSyncStatus,
                    activeColor = GoldAura400
                )
            }

            // 5. Comment (with commentCount badge and 95% listening gate)
            InteractionItem(
                icon = if (isCommentEnabled) Icons.Rounded.ChatBubble else Icons.Rounded.ChatBubbleOutline,
                label = "Comment",
                onClick = onCommentClick,
                badgeCount = if (commentCount > 0L) commentCount else null,
                badgeProminent = isCommentEnabled,
                isActive = isCommentEnabled,
                enabled = isCommentEnabled,
                disabledReason = commentDisabledReason,
                activeColor = GoldAura400
            )
        }
    }
}

/**
 * Reusable container that attaches a numerical badge near the upper-right corner of a 48dp action icon.
 * Ensures the badge remains strictly within the bounds of the interaction item and does not get clipped (Phase 2.18.2B).
 */
@Composable
private fun ToolbarBadgeContainer(
    modifier: Modifier = Modifier,
    badge: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(48.dp)
    ) {
        content()
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
            ) {
                badge()
            }
        }
    }
}

/**
 * Authoritative circular red count badge for persistent numerical counters.
 * Features circular red background, centered bold white text, consistent minimum dimensions,
 * and adequate horizontal padding for multi-digit values (Phase 2.18.2B).
 */
@Composable
fun CountBadge(
    count: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentDescription: String? = null,
    isProminent: Boolean = true
) {
    val formattedCount = remember(count) {
        java.text.NumberFormat.getIntegerInstance().format(count)
    }

    val clickableModifier = if (onClick != null) {
        Modifier
            .clip(CircleShape)
            .clickable(onClickLabel = onClickLabel) { onClick() }
    } else {
        Modifier.clip(CircleShape)
    }

    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }

    val backgroundColor = if (isProminent) {
        CountBadgeBackground
    } else {
        CountBadgeBackground.copy(alpha = 0.5f)
    }

    val textColor = if (isProminent) {
        CountBadgeText
    } else {
        CountBadgeText.copy(alpha = 0.7f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(clickableModifier)
            .then(semanticsModifier)
            .background(backgroundColor, CircleShape)
            .heightIn(min = 16.dp)
            .widthIn(min = 16.dp)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = formattedCount,
            color = textColor,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 11.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

/**
 * Authoritative qualified listening count presentation in the bottom interaction toolbar.
 * Displays headphones icon, attached circular red count badge, and single-line "Listens" label.
 */
@Composable
fun QualifiedPlayCountToolbarItem(
    count: Long,
    modifier: Modifier = Modifier
) {
    val formattedCount = remember(count) {
        java.text.NumberFormat.getIntegerInstance().format(count)
    }
    val unit = if (count == 1L) "qualified listen" else "qualified listens"
    val fullDescription = "$formattedCount $unit"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = fullDescription
            }
    ) {
        ToolbarBadgeContainer(
            badge = { CountBadge(count = count) }
        ) {
            Icon(
                imageVector = Icons.Rounded.Headphones,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "Listens",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ResonateInteractionItem(
    isResonated: Boolean,
    resonanceCount: Int,
    canShowResonators: Boolean,
    onResonateClick: () -> Unit,
    onCountClick: () -> Unit,
    modifier: Modifier = Modifier,
    syncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    isOwner: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val showBadge = canShowResonators && resonanceCount > 0
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

    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
        label = "ResonateTintAnimation"
    )

    val resonateContentDescription = if (!enabled) {
        "You cannot resonate with your own reflection"
    } else if (syncStatus == InteractionSyncStatus.PENDING) {
        "Resonating..."
    } else if (isResonated) {
        "Resonated with this reflection"
    } else {
        "Resonate with this reflection"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        ToolbarBadgeContainer(
            badge = if (showBadge) {
                {
                    CountBadge(
                        count = resonanceCount.toLong(),
                        onClick = onCountClick,
                        onClickLabel = "View resonators",
                        contentDescription = "$resonanceCount Resonators. Tap to view resonators."
                    )
                }
            } else null
        ) {
            // 1. Resonate Heart Toggle Target (Guaranteed >= 48dp x 48dp touch target)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        onClickLabel = resonateContentDescription
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
                    contentDescription = resonateContentDescription,
                    tint = animatedContentColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "Resonate",
            style = MaterialTheme.typography.labelSmall,
            color = animatedContentColor,
            fontSize = 11.sp,
            fontWeight = if (isResonated) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InteractionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Long? = null,
    badgeProminent: Boolean = true,
    isActive: Boolean = false,
    syncStatus: InteractionSyncStatus = InteractionSyncStatus.SYNCED,
    enabled: Boolean = true,
    disabledReason: String? = null,
    loading: Boolean = false,
    activeColor: Color = GoldAura400
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val contentColor = if (!enabled && !loading) {
        Color.White.copy(alpha = 0.38f)
    } else if (isActive) {
        if (syncStatus == InteractionSyncStatus.PENDING) activeColor.copy(alpha = 0.85f) else activeColor
    } else {
        if (syncStatus == InteractionSyncStatus.PENDING) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.45f)
    }

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ScaleAnimation"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled || disabledReason != null) {
                if (enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                } else if (disabledReason != null) {
                    FeedbackUtils.explainDisabledAction(
                        context,
                        haptic,
                        disabledReason
                    )
                }
            }
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        ToolbarBadgeContainer(
            badge = if (badgeCount != null && badgeCount > 0L) {
                {
                    CountBadge(
                        count = badgeCount,
                        contentDescription = "$badgeCount comments",
                        isProminent = badgeProminent
                    )
                }
            } else null
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier
                    .size(28.dp)
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

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}
