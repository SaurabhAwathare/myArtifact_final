package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.User
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.components.ArtifactSigil
import com.saurabh.artifact.model.SigilConfig
import com.saurabh.artifact.ui.components.state.LoadingPlaceholder
import com.saurabh.artifact.ui.theme.Spacing

/**
 * Refined ProfileHeader adhering to Stage 3.10.4 visual hierarchy and interaction requirements:
 * - Sigil (64dp interactive/accessible) and anonymous name + handle arranged with clear visual breathing room.
 * - Profile description / bio supported when present with calm, readable typography.
 * - Follow / Resonate button satisfying 48dp minimum interactive touch target requirement.
 * - Clear distinction and spacing between identity information and statistics row.
 * - Stats (Artifacts, Following, Resonators) evenly spaced with 48dp touch targets.
 */
@Composable
fun ProfileHeader(
    user: User?,
    sigilConfig: SigilConfig,
    isSelf: Boolean,
    isResonating: Boolean,
    onResonateClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
    onResonatorsClick: () -> Unit = {},
    onResonatingClick: () -> Unit = {},
    isLoading: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = Spacing.Medium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top Row: Sigil on the left, Profile Name, Handle & Actions on the right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Sigil (Compact size 64.dp, minimum 48dp touch target satisfied)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clickable(enabled = isSelf && !isLoading) { onEditClick() },
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    LoadingPlaceholder(
                        height = 64.dp,
                        width = Modifier.size(64.dp),
                        shape = CircleShape
                    )
                } else {
                    val displayConfig = when {
                        isSelf -> sigilConfig
                        user != null -> user.sigilConfig
                        else -> SigilConfig(seed = "fallback")
                    }

                    ArtifactSigil(
                        config = displayConfig,
                        size = 64.dp
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.Medium))

            // Username, Handle and Follow button column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                if (isLoading) {
                    LoadingPlaceholder(
                        height = 20.dp,
                        width = Modifier.width(120.dp),
                        shape = RoundedCornerShape(4.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.ExtraSmall))
                    LoadingPlaceholder(
                        height = 14.dp,
                        width = Modifier.width(70.dp),
                        shape = RoundedCornerShape(4.dp)
                    )
                } else {
                    Text(
                        text = user?.anonymousName ?: "quiet presence",
                        style = ArtifactTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = ArtifactTheme.colors.onSurfaceMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val handle = user?.anonymousSigil?.takeIf { it.isNotBlank() }
                    if (handle != null) {
                        Spacer(modifier = Modifier.height(Spacing.ExtraSmall))
                        Text(
                            text = handle,
                            style = ArtifactTheme.typography.labelMedium,
                            color = ArtifactTheme.colors.onSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!isSelf) {
                    Spacer(modifier = Modifier.height(Spacing.Small))
                    Button(
                        onClick = onResonateClick,
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isResonating) ArtifactTheme.colors.surfaceHearth else MaterialTheme.colorScheme.primary,
                            contentColor = if (isResonating) ArtifactTheme.colors.onSurfaceMain else MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = ArtifactTheme.colors.surfaceHearth.copy(alpha = 0.5f),
                            disabledContentColor = ArtifactTheme.colors.onSurfaceMuted.copy(alpha = 0.5f)
                        ),
                        border = if (isResonating) BorderStroke(1.dp, ArtifactTheme.colors.waveformInactive) else null,
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                    ) {
                        Text(
                            text = if (isResonating) "Following" else "Follow",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Profile Description / Bio (if present)
        val bio = user?.bio?.takeIf { it.isNotBlank() }
        if (bio != null) {
            Spacer(modifier = Modifier.height(Spacing.Medium))
            Text(
                text = bio,
                style = ArtifactTheme.typography.bodyMedium,
                color = ArtifactTheme.colors.onSurfaceMain.copy(alpha = 0.85f),
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.ExtraSmall)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.Large))

        // Stats Row (Artifacts, Following, Resonators)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                repeat(3) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingPlaceholder(height = 18.dp, width = Modifier.width(30.dp))
                        Spacer(Modifier.height(4.dp))
                        LoadingPlaceholder(height = 12.dp, width = Modifier.width(60.dp))
                    }
                }
            } else {
                val resonatorsCount = when {
                    user == null -> 0L
                    user.resonanceInCount > 0 -> user.resonanceInCount
                    else -> user.followersCount
                }
                val followingCount = when {
                    user == null -> 0L
                    user.resonanceOutCount > 0 -> user.resonanceOutCount
                    else -> user.followingCount
                }

                StatItem(
                    label = "artifacts",
                    displayValue = (user?.artifactsCount ?: 0L).toString()
                )
                StatItem(
                    label = "following", 
                    displayValue = followingCount.toString(),
                    onClick = onResonatingClick,
                    enabled = isSelf
                )
                StatItem(
                    label = "resonators", 
                    displayValue = resonatorsCount.toString(),
                    onClick = onResonatorsClick,
                    enabled = isSelf
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    displayValue: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = Spacing.Small, vertical = Spacing.ExtraSmall),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = displayValue,
            style = ArtifactTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ArtifactTheme.colors.onSurfaceMain
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label.lowercase(),
            style = ArtifactTheme.typography.labelSmall,
            color = ArtifactTheme.colors.onSurfaceMuted,
            modifier = Modifier.alpha(0.7f)
        )
    }
}

@Preview(name = "Own Profile Header")
@Composable
private fun ProfileHeaderSelfPreview() {
    ArtifactTheme {
        Surface(color = ArtifactTheme.colors.surfaceLoom) {
            ProfileHeader(
                user = User(
                    anonymousName = "Quiet Wanderer",
                    anonymousSigil = "PHX-77",
                    bio = "Listening to the echoes of dawn across the quiet valley.",
                    artifactsCount = 12,
                    followingCount = 4,
                    followersCount = 28
                ),
                sigilConfig = SigilConfig(seed = "sample"),
                isSelf = true,
                isResonating = false,
                onResonateClick = {},
                onEditClick = {}
            )
        }
    }
}

@Preview(name = "External Profile Header - Not Following")
@Composable
private fun ProfileHeaderExternalPreview() {
    ArtifactTheme {
        Surface(color = ArtifactTheme.colors.surfaceLoom) {
            ProfileHeader(
                user = User(
                    anonymousName = "Luminous Spirit",
                    anonymousSigil = "LUM-09",
                    bio = "Archiving ambient thoughts and rainy morning echoes.",
                    artifactsCount = 5,
                    followingCount = 2,
                    followersCount = 19
                ),
                sigilConfig = SigilConfig(seed = "luminous"),
                isSelf = false,
                isResonating = false,
                onResonateClick = {},
                onEditClick = {}
            )
        }
    }
}

@Preview(name = "External Profile Header - Following")
@Composable
private fun ProfileHeaderFollowingPreview() {
    ArtifactTheme {
        Surface(color = ArtifactTheme.colors.surfaceLoom) {
            ProfileHeader(
                user = User(
                    anonymousName = "Luminous Spirit",
                    anonymousSigil = "LUM-09",
                    bio = "Archiving ambient thoughts and rainy morning echoes.",
                    artifactsCount = 5,
                    followingCount = 2,
                    followersCount = 20
                ),
                sigilConfig = SigilConfig(seed = "luminous"),
                isSelf = false,
                isResonating = true,
                onResonateClick = {},
                onEditClick = {}
            )
        }
    }
}

@Preview(name = "Profile Header - Loading")
@Composable
private fun ProfileHeaderLoadingPreview() {
    ArtifactTheme {
        Surface(color = ArtifactTheme.colors.surfaceLoom) {
            ProfileHeader(
                user = null,
                sigilConfig = SigilConfig(),
                isSelf = false,
                isResonating = false,
                onResonateClick = {},
                onEditClick = {},
                isLoading = true
            )
        }
    }
}

