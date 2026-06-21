package com.saurabh.artifact.ui.profile.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.saurabh.artifact.model.User
import com.saurabh.artifact.ui.theme.ArtifactTheme

import com.saurabh.artifact.ui.components.ArtifactAvatar
import com.saurabh.artifact.model.AvatarConfig

/**
 * Redesigned ProfileHeader: Compact, Dense, Instagram-style hierarchy.
 * [ Avatar ]   Posts   Resonating   Resonators
 * @username
 */
@Composable
fun ProfileHeader(
    user: User?,
    avatarConfig: AvatarConfig,
    isSelf: Boolean,
    isResonating: Boolean,
    onResonateClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
    onResonatorsClick: () -> Unit = {},
    onResonatingClick: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clickable(enabled = isSelf) { onEditClick() },
            contentAlignment = Alignment.Center
        ) {
            val displayConfig = when {
                isSelf -> avatarConfig
                user != null -> user.avatarConfig
                else -> AvatarConfig(seed = "fallback")
            }

            ArtifactAvatar(
                config = displayConfig,
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
                count = user?.artifactsCount ?: 0L
            )
            StatItem(
                label = "following", 
                count = followingCount,
                onClick = onResonatingClick
            )
            StatItem(
                label = "resonators", 
                count = resonatorsCount,
                onClick = onResonatorsClick
            )
        }

        if (!isSelf) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onResonateClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isResonating) ArtifactTheme.colors.surfaceHearth else MaterialTheme.colorScheme.primary,
                    contentColor = if (isResonating) ArtifactTheme.colors.onSurfaceMain else MaterialTheme.colorScheme.onPrimary
                ),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text(if (isResonating) "Following" else "Follow")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, count: Long, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = true) { onClick() }
    ) {
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
