package com.saurabh.artifact.ui.profile.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.artifact.model.User
import com.saurabh.artifact.ui.theme.ArtifactTheme
import com.saurabh.artifact.ui.theme.GoldAura500
import com.saurabh.artifact.ui.theme.Spacing

import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.model.AvatarConfig

/**
 * Redesigned ProfileHeader: Compact, Dense, Instagram-style hierarchy.
 * [ Avatar ]   Posts   Followers   Following
 * @username
 */
@Composable
fun ProfileHeader(
    user: User?,
    avatarConfig: AvatarConfig,
    postCount: Int,
    isSelf: Boolean,
    isFollowing: Boolean,
    onFollowClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Large Avatar
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .clickable(enabled = isSelf) { onEditClick() },
            contentAlignment = Alignment.Center
        ) {
            ArtifactAvatar(
                config = if (isSelf) avatarConfig else user?.avatarConfig ?: AvatarConfig(seed = user?.avatarSeed ?: ""),
                size = 110.dp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Username
        Text(
            text = user?.anonymousName ?: "quiet presence",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(label = "followers", count = user?.followersCount ?: 0)
            StatItem(label = "following", count = user?.followingCount ?: 0)
            StatItem(label = "streak", count = 5) // Mock streak for now
        }
    }
}

@Composable
private fun StatItem(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ArtifactTheme.colors.onSurfaceMain
        )
        Text(
            text = label.lowercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ArtifactTheme.colors.onSurfaceMuted,
            modifier = Modifier.alpha(0.6f)
        )
    }
}
